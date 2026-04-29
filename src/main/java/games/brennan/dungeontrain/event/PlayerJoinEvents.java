package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PrefabRegistrySyncPacket;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.tunnel.TunnelGenerator;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.StartingDimension;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

/**
 * Teleports joining players to natural ground beside the corridor near
 * the train, looking at the nearest above-ground stretch of track.
 *
 * <p>Spawn target:</p>
 * <ol>
 *   <li>Resolve the train's world center via the kinematic driver's
 *       {@code canonicalPos} (Sable's {@code currentWorldPosition()} can
 *       return shipyard-translation coordinates rather than world-space
 *       pivot, which historically sent the perpendicular-offset search
 *       hunting for ground at +20M block coords).</li>
 *   <li>Walk +X (away from origin) along the corridor center until a
 *       column is non-tunnel-qualified — the "reference point" of the
 *       nearest above-ground track. Falls back to scanning −X if the +X
 *       direction stays buried for {@link #MAX_ABOVEGROUND_SCAN} blocks.</li>
 *   <li>From that reference point, pick a random perpendicular Z offset of
 *       {@link #PERP_MIN}..{@link #PERP_MAX} on a random side, and a small
 *       ±X jitter so multiple players don't stack.</li>
 *   <li>Walk down through air / fluid / leaves / vines to find the surface,
 *       and validate the candidate isn't void / against the ceiling / in
 *       a fluid column. Re-roll up to {@link #MAX_ATTEMPTS} times if
 *       invalid.</li>
 *   <li>If every attempt fails (e.g. corner case ocean-only biome),
 *       fall back to the reference point's surface column directly.</li>
 * </ol>
 *
 * <p>The look-at target is the reference point (above-ground track
 * surface), so the player faces the corridor whether or not the train
 * itself is currently in a tunnel.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerJoinEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Max ±X jitter around the reference point on each spawn attempt. */
    private static final double X_JITTER_MAX = 30.0;

    /** Perpendicular distance from the corridor centerline. */
    private static final double PERP_MIN = 10.0;
    private static final double PERP_MAX = 40.0;

    private static final int MAX_ATTEMPTS = 20;
    private static final int VOID_CLEARANCE = 5;
    private static final int CEILING_CLEARANCE = 10;

    /** Max blocks to scan looking for an above-ground (non-tunnel) column. */
    private static final int MAX_ABOVEGROUND_SCAN = 512;

    private PlayerJoinEvents() {}

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        DungeonTrainNet.sendTo(player, PrefabRegistrySyncPacket.fromRegistries());

        if (!(player.level() instanceof ServerLevel currentLevel)) return;

        ServerLevel overworld = currentLevel.getServer().overworld();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        StartingDimension startingDim = data.startingDimension();
        ServerLevel trainLevel = currentLevel.getServer().getLevel(startingDim.levelKey());
        if (trainLevel == null) {
            LOGGER.warn("[DungeonTrain] Starting dimension {} not loaded on login — skipping teleport for {}",
                startingDim, player.getName().getString());
            return;
        }

        ManagedShip trainShip = findTrain(trainLevel);
        if (trainShip == null) {
            LOGGER.info("[DungeonTrain] No train present in {} on login — skipping teleport for {}",
                trainLevel.dimension().location(), player.getName().getString());
            return;
        }

        TrainTransformProvider provider =
            (TrainTransformProvider) trainShip.getKinematicDriver();
        Vector3d trainCenter = resolveTrainCenter(provider, data);

        TrackGeometry g = TrackGeometry.from(data.dims(), data.getTrainY());
        TunnelGeometry tg = TunnelGeometry.from(g);

        // Reference point: the nearest above-ground column starting from the
        // train's current X. Scans +X first (away from origin in the train's
        // travel direction) and falls back to −X if +X stays buried.
        int trainX = (int) Math.floor(trainCenter.x);
        int referenceX = findAboveGroundReferenceX(trainLevel, trainX, tg);

        Vec3 referencePoint = new Vec3(referenceX + 0.5, g.bedY() + 1.5, g.trackCenterZ() + 0.5);

        PlayerTarget target = pickPlayerTarget(trainLevel, referenceX, g);
        LOGGER.info("[DungeonTrain] Login spawn for {}: pos=({}, {}, {}) lookAt=({}, {}, {}) trainCenter=({}, {}, {}) refX={}",
            player.getName().getString(),
            String.format("%.1f", target.px), target.py, String.format("%.1f", target.pz),
            String.format("%.1f", referencePoint.x), String.format("%.1f", referencePoint.y), String.format("%.1f", referencePoint.z),
            String.format("%.1f", trainCenter.x), String.format("%.1f", trainCenter.y), String.format("%.1f", trainCenter.z),
            referenceX);

        player.teleportTo(trainLevel, target.px, target.py, target.pz, 0f, 0f);
        player.lookAt(EntityAnchorArgument.Anchor.EYES, referencePoint);
    }

    /**
     * Resolve the train's world-space center from the kinematic driver's
     * {@code canonicalPos}, falling back to the spawn anchor if the driver
     * hasn't ticked yet.
     */
    private static Vector3d resolveTrainCenter(
        TrainTransformProvider provider, DungeonTrainWorldData data
    ) {
        if (provider != null) {
            Vector3dc canonical = provider.getCanonicalPos();
            if (canonical != null) {
                return new Vector3d(canonical.x(), canonical.y(), canonical.z());
            }
        }
        TrackGeometry g = TrackGeometry.from(data.dims(), data.getTrainY());
        return new Vector3d(0.0, data.getTrainY(), g.trackCenterZ());
    }

    /**
     * Walk +X (away from origin) along the corridor center looking for the
     * nearest non-tunnel-qualified column. Falls back to −X if the +X
     * direction stays buried for {@link #MAX_ABOVEGROUND_SCAN} blocks.
     * Returns {@code originX} unchanged if it's already above-ground.
     * Returns {@code originX} as a last resort if both directions are
     * tunnel-qualified for the entire scan range.
     */
    private static int findAboveGroundReferenceX(
        ServerLevel level, int originX, TunnelGeometry tg
    ) {
        if (!TunnelGenerator.isColumnUnderground(level, originX, tg)) {
            return originX;
        }
        // +X first ("away from 0" in the train's travel direction).
        for (int dx = 1; dx <= MAX_ABOVEGROUND_SCAN; dx++) {
            int forward = originX + dx;
            if (!TunnelGenerator.isColumnUnderground(level, forward, tg)) return forward;
        }
        // −X fallback.
        for (int dx = 1; dx <= MAX_ABOVEGROUND_SCAN; dx++) {
            int backward = originX - dx;
            if (!TunnelGenerator.isColumnUnderground(level, backward, tg)) return backward;
        }
        return originX;
    }

    private static ManagedShip findTrain(ServerLevel level) {
        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            if (ship.getKinematicDriver() instanceof TrainTransformProvider) {
                return ship;
            }
        }
        return null;
    }

    /**
     * Random offset around the reference point. Re-rolls up to
     * {@link #MAX_ATTEMPTS} times if the candidate is in water or otherwise
     * invalid. Falls back to the reference column's surface if every attempt
     * fails (e.g. player happens to be in a deep-ocean biome).
     */
    private static PlayerTarget pickPlayerTarget(ServerLevel level, int referenceX, TrackGeometry g) {
        RandomSource rand = level.getRandom();
        double centerZ = g.trackCenterZ() + 0.5;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            double xOffset = (rand.nextDouble() * 2.0 - 1.0) * X_JITTER_MAX;
            double sideSign = rand.nextBoolean() ? 1.0 : -1.0;
            double perpDist = PERP_MIN + rand.nextDouble() * (PERP_MAX - PERP_MIN);
            double zOffset = sideSign * perpDist;

            double px = referenceX + 0.5 + xOffset;
            double pz = centerZ + zOffset;

            int bx = Mth.floor(px);
            int bz = Mth.floor(pz);
            level.getChunk(bx >> 4, bz >> 4, ChunkStatus.FULL, true);

            int groundY = findGroundY(level, bx, bz);
            int playerY = groundY + 1;
            if (isSafePlayerPos(level, bx, playerY, bz)) {
                return new PlayerTarget(px, playerY, pz);
            }
        }

        // Fallback: drop the player on the reference column's surface.
        // Force-load that column's chunk too.
        level.getChunk(referenceX >> 4, g.trackCenterZ() >> 4, ChunkStatus.FULL, true);
        int fallbackGroundY = findGroundY(level, referenceX, g.trackCenterZ());
        int fallbackY = fallbackGroundY > level.getMinBuildHeight()
            ? fallbackGroundY + 1
            : g.bedY() + 1;
        LOGGER.warn("[DungeonTrain] pickPlayerTarget exhausted {} attempts — falling back to reference column at Y={}",
            MAX_ATTEMPTS, fallbackY);
        return new PlayerTarget(referenceX + 0.5, fallbackY, centerZ);
    }

    /**
     * Walk down from the world ceiling through air/fluid/leaves/vines until
     * a solid block is hit. Returns the Y of that block (ground Y); caller
     * stands the player at {@code groundY + 1}. Returns
     * {@code level.getMinBuildHeight() - 1} (sentinel: no ground found) if
     * every scanned block is passable.
     */
    private static int findGroundY(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        int startY = level.getMaxBuildHeight() - 1;
        for (int y = startY; y >= minY; y--) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (!isPassable(state)) {
                return y;
            }
        }
        return minY - 1;
    }

    /**
     * Mirrors {@code TrackGenerator.isPassable}: the player ground probe
     * treats air, fluids (water/lava), leaves, and vines as "keep descending"
     * — any other block counts as ground.
     */
    private static boolean isPassable(BlockState state) {
        return state.isAir()
            || !state.getFluidState().isEmpty()
            || state.is(BlockTags.LEAVES)
            || state.is(Blocks.VINE);
    }

    /**
     * Validates a candidate player position. Rejects positions in the void,
     * against the ceiling, or with water/lava at body/head level (so the
     * player never spawns submerged even if the ground probe found a solid
     * seabed under a deep ocean column).
     */
    private static boolean isSafePlayerPos(ServerLevel level, int x, int y, int z) {
        if (y < level.getMinBuildHeight() + VOID_CLEARANCE) return false;
        if (y > level.getMaxBuildHeight() - CEILING_CLEARANCE) return false;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.set(x, y, z);
        if (!level.getBlockState(pos).getFluidState().isEmpty()) return false;
        pos.set(x, y + 1, z);
        if (!level.getBlockState(pos).getFluidState().isEmpty()) return false;
        return true;
    }

    private record PlayerTarget(double px, int py, double pz) {}
}
