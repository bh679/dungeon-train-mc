package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

/**
 * Teleports joining players to a random position alongside the current
 * Dungeon Train, camera aimed at the ship centre.
 *
 * <p>Train assembly itself lives in
 * {@link TrainBootstrapEvents#onServerStarted} — by the time
 * {@link PlayerEvent.PlayerLoggedInEvent} fires the train is already in the
 * world, so this handler only has to pick a safe ground position and run a
 * single teleport. If the bootstrap skipped (startsWithTrain=false or
 * assembly failed) no train exists and this handler is a no-op.</p>
 *
 * <p>Placement: ±{@link #X_OFFSET_MAX} along the train's travel axis,
 * {@link #PERP_MIN}..{@link #PERP_MAX} on a random side. The Y coordinate is
 * found by a custom ground probe ({@link #findGroundY}) that skips air,
 * water/lava, leaves, and vines — the same passability shape used by
 * {@code TrackGenerator} — so players never land inside ocean water, on top
 * of a tree canopy, or below the world floor. Candidates are re-rolled up to
 * {@link #MAX_ATTEMPTS} times if the spot fails validation
 * ({@link #isSafePlayerPos}); the last-resort fallback drops the player on
 * top of the train, which is always safe by construction.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerJoinEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double X_OFFSET_MAX = 200.0;
    private static final double PERP_MIN = 10.0;
    private static final double PERP_MAX = 40.0;

    private static final int MAX_ATTEMPTS = 20;
    private static final int VOID_CLEARANCE = 5;
    private static final int CEILING_CLEARANCE = 10;

    private PlayerJoinEvents() {}

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;

        ManagedShip trainShip = findTrain(level);
        if (trainShip == null) {
            LOGGER.info("[DungeonTrain] No train present on login — skipping teleport for {}",
                player.getName().getString());
            return;
        }

        Vector3dc trainPos = trainShip.currentWorldPosition();
        Vector3d trainCenter = new Vector3d(trainPos.x(), trainPos.y(), trainPos.z());
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level.getServer().overworld());
        PlayerTarget target = pickPlayerTarget(level, trainCenter, data);
        teleportAndLookAt(level, player, trainShip, target);
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
     * Random offset around {@code trainCenter}, with the Y resolved to a
     * safe ground position. Retries with fresh offsets up to
     * {@link #MAX_ATTEMPTS} times if the candidate is in water or otherwise
     * invalid. Falls back to standing on top of the train if every attempt
     * fails (e.g. player lands in a full-ocean biome).
     */
    private static PlayerTarget pickPlayerTarget(ServerLevel level, Vector3dc trainCenter, DungeonTrainWorldData data) {
        RandomSource rand = level.getRandom();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            double xOffset = (rand.nextDouble() * 2.0 - 1.0) * X_OFFSET_MAX;
            double sideSign = rand.nextBoolean() ? 1.0 : -1.0;
            double perpDist = PERP_MIN + rand.nextDouble() * (PERP_MAX - PERP_MIN);
            double zOffset = sideSign * perpDist;

            double px = trainCenter.x() + xOffset;
            double pz = trainCenter.z() + zOffset;

            int bx = Mth.floor(px);
            int bz = Mth.floor(pz);
            level.getChunk(bx >> 4, bz >> 4, ChunkStatus.FULL, true);

            int groundY = findGroundY(level, bx, bz);
            int playerY = groundY + 1;
            if (isSafePlayerPos(level, bx, playerY, bz)) {
                return new PlayerTarget(px, playerY, pz);
            }
        }

        CarriageDims dims = data.dims();
        int fallbackY = data.getTrainY() + dims.height() + 2;
        LOGGER.warn("[DungeonTrain] pickPlayerTarget exhausted {} attempts — falling back to top of train (Y={})",
            MAX_ATTEMPTS, fallbackY);
        return new PlayerTarget(trainCenter.x(), fallbackY, trainCenter.z());
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
        // Body and head must be fluid-free — don't drop the player into an ocean column.
        pos.set(x, y, z);
        if (!level.getBlockState(pos).getFluidState().isEmpty()) return false;
        pos.set(x, y + 1, z);
        if (!level.getBlockState(pos).getFluidState().isEmpty()) return false;
        return true;
    }

    private static void teleportAndLookAt(
        ServerLevel level,
        ServerPlayer player,
        ManagedShip ship,
        PlayerTarget target
    ) {
        Vector3dc trainPos = ship.currentWorldPosition();

        player.teleportTo(level, target.px, target.py, target.pz, 0f, 0f);
        Vec3 lookTarget = new Vec3(trainPos.x(), trainPos.y() + 1.5, trainPos.z());
        player.lookAt(EntityAnchorArgument.Anchor.EYES, lookTarget);

        LOGGER.info("[DungeonTrain] Placed {} at ({},{},{}) looking at train centre ({},{},{})",
            player.getName().getString(),
            String.format("%.1f", target.px), target.py, String.format("%.1f", target.pz),
            String.format("%.1f", trainPos.x()), String.format("%.1f", trainPos.y()), String.format("%.1f", trainPos.z()));
    }

    private record PlayerTarget(double px, int py, double pz) {}
}
