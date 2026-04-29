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
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
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
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Teleports joining players to natural ground beside the corridor near
 * the train, looking at a clear above-ground stretch of track.
 *
 * <p>Placement is deferred to a {@link LevelTickEvent.Post} retry loop:
 * on a fresh world {@code TrainBootstrapEvents.onServerStarted} runs
 * synchronously, but Sable's {@code Shipyards.findAll} can return empty
 * for one or more ticks afterward (the wrapper only sees the new ship
 * after Sable's internal manager pumps). Players queued at
 * {@link PlayerEvent.PlayerLoggedInEvent} retry on each overworld tick
 * until the train resolves or {@link #MAX_RETRY_TICKS} elapses.</p>
 *
 * <p>Spawn target:</p>
 * <ol>
 *   <li>Resolve the train's world center via the kinematic driver's
 *       {@code canonicalPos} (Sable's {@code currentWorldPosition()} can
 *       return shipyard-translation coordinates rather than world-space
 *       pivot).</li>
 *   <li>Look for an X with at least {@link #X_BUFFER} non-tunnel columns
 *       on each side, scanning +X first then −X, capped at
 *       {@link #MAX_ABOVEGROUND_SCAN}. This X is BOTH the spawn anchor
 *       AND the look target, so the player is spawned well past any
 *       tunnel mouth.</li>
 *   <li>If no buffered window exists in scan range (genuinely-buried
 *       corridor), fall back: anchor at the train's X (player is on the
 *       mountain top above the buried train) and look at the first
 *       non-tunnel X (or the train's X if even that fails).</li>
 *   <li>Random perpendicular Z offset {@link #PERP_MIN}..{@link #PERP_MAX}
 *       on a random side, ±{@link #X_JITTER_MAX} X jitter so multiple
 *       players don't stack.</li>
 *   <li>Walk down through air / fluid / leaves / vines to find the surface;
 *       reject void / ceiling / fluid columns; re-roll up to
 *       {@link #MAX_ATTEMPTS} times. The fallback uses {@link #PERP_MIN}
 *       perpendicular offset — never the corridor centerline — so the
 *       player never lands on the tracks.</li>
 * </ol>
 *
 * <p>Yaw and pitch are computed from the (player → reference point) vector
 * and passed to {@code teleportTo} directly, avoiding the post-teleport
 * rotation race {@code lookAt} introduces.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerJoinEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Max ±X jitter around the spawn anchor per attempt. */
    private static final double X_JITTER_MAX = 30.0;

    /** Perpendicular distance from the corridor centerline. */
    private static final double PERP_MIN = 10.0;
    private static final double PERP_MAX = 40.0;

    /**
     * Min non-tunnel columns required on each side of a candidate
     * reference X. Must exceed {@link #X_JITTER_MAX} so jittered spawns
     * stay inside the clear window.
     */
    private static final int X_BUFFER = 40;

    private static final int MAX_ATTEMPTS = 20;
    private static final int VOID_CLEARANCE = 5;
    private static final int CEILING_CLEARANCE = 10;

    /** Max blocks to scan looking for a buffered above-ground window. */
    private static final int MAX_ABOVEGROUND_SCAN = 128;

    /**
     * Max perpendicular distance to walk searching for dry ground when the
     * random retry loop has exhausted its attempts (e.g. corridor crosses
     * a deep ocean). Hard cap so we don't pin the server thread.
     */
    private static final int MAX_FALLBACK_PERP = 256;

    /** Standing eye height (blocks above feet) — used for pitch math. */
    private static final double EYE_HEIGHT = 1.62;

    /**
     * Max ticks to keep retrying the train lookup after login. 20 TPS,
     * so 100 ≈ 5 s — generous; in practice the race resolves in 1–2 ticks.
     */
    private static final int MAX_RETRY_TICKS = 100;

    /** Player UUID → tick count of pending retry. Cleared on success/timeout/logout. */
    private static final Map<UUID, Integer> PENDING = new ConcurrentHashMap<>();

    private PlayerJoinEvents() {}

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        DungeonTrainNet.sendTo(player, PrefabRegistrySyncPacket.fromRegistries());
        PENDING.put(player.getUUID(), 0);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PENDING.remove(player.getUUID());
        }
    }

    /**
     * Once-per-server-tick processor for the pending-login queue. Anchored
     * on the overworld tick so we don't double-process across multiple
     * dimensions (placement is dimension-agnostic — it redirects to the
     * starting dimension).
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;

        MinecraftServer server = level.getServer();
        Iterator<Map.Entry<UUID, Integer>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            if (tryPlace(player)) {
                it.remove();
                continue;
            }
            int ticks = entry.getValue() + 1;
            if (ticks >= MAX_RETRY_TICKS) {
                LOGGER.warn("[DungeonTrain] Login spawn placement timed out for {} after {} ticks — train never appeared",
                    player.getName().getString(), MAX_RETRY_TICKS);
                it.remove();
            } else {
                entry.setValue(ticks);
            }
        }
    }

    /**
     * Try to place {@code player}. Returns {@code true} if a placement
     * decision was made (success or skip-no-recovery), {@code false} if
     * the train wasn't resolvable yet and we should retry next tick.
     */
    private static boolean tryPlace(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel currentLevel)) return true;

        ServerLevel overworld = currentLevel.getServer().overworld();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        StartingDimension startingDim = data.startingDimension();
        ServerLevel trainLevel = currentLevel.getServer().getLevel(startingDim.levelKey());
        if (trainLevel == null) {
            LOGGER.warn("[DungeonTrain] Starting dimension {} not loaded — skipping teleport for {}",
                startingDim, player.getName().getString());
            return true;
        }

        ManagedShip trainShip = findTrain(trainLevel);
        if (trainShip == null) return false; // retry next tick

        TrainTransformProvider provider =
            (TrainTransformProvider) trainShip.getKinematicDriver();
        Vector3d trainCenter = resolveTrainCenter(provider, data);

        TrackGeometry g = TrackGeometry.from(data.dims(), data.getTrainY());
        TunnelGeometry tg = TunnelGeometry.from(g);

        int trainX = (int) Math.floor(trainCenter.x);

        // Try to find an X with X_BUFFER non-tunnel columns on each side.
        // If found, use it as BOTH the spawn anchor and the look target so
        // the player is spawned well past any tunnel mouth.
        int bufferedX = findBufferedReferenceX(trainLevel, trainX, tg);
        boolean haveBuffered = bufferedX != Integer.MIN_VALUE;

        int anchorX = haveBuffered ? bufferedX : trainX;
        int lookX = haveBuffered ? bufferedX : findAboveGroundReferenceX(trainLevel, trainX, tg);

        PlayerTarget target = pickPlayerTarget(trainLevel, anchorX, g, trainCenter);
        Vec3 referencePoint = new Vec3(lookX + 0.5, g.bedY() + 1.5, g.trackCenterZ() + 0.5);

        // Compute yaw/pitch explicitly from (target → referencePoint) and
        // pass them straight to teleportTo. Avoids the post-teleport
        // rotation race a separate lookAt() call would create.
        double dx = referencePoint.x - target.px;
        double dy = referencePoint.y - (target.py + EYE_HEIGHT);
        double dz = referencePoint.z - target.pz;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));

        LOGGER.info("[DungeonTrain] Login spawn for {}: pos=({}, {}, {}) lookAt=({}, {}, {}) yaw={} pitch={} trainCenter=({}, {}, {}) anchorX={} lookX={} buffered={}",
            player.getName().getString(),
            String.format("%.1f", target.px), target.py, String.format("%.1f", target.pz),
            String.format("%.1f", referencePoint.x), String.format("%.1f", referencePoint.y), String.format("%.1f", referencePoint.z),
            String.format("%.1f", yaw), String.format("%.1f", pitch),
            String.format("%.1f", trainCenter.x), String.format("%.1f", trainCenter.y), String.format("%.1f", trainCenter.z),
            anchorX, lookX, haveBuffered);

        player.teleportTo(trainLevel, target.px, target.py, target.pz, yaw, pitch);
        return true;
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
     * Find an X with at least {@link #X_BUFFER} non-tunnel columns on each
     * side. Scans +X first, then −X. Returns {@link Integer#MIN_VALUE} if
     * no such buffered window exists in {@link #MAX_ABOVEGROUND_SCAN}
     * blocks in either direction.
     */
    private static int findBufferedReferenceX(
        ServerLevel level, int originX, TunnelGeometry tg
    ) {
        int found = scanBufferedDir(level, originX, tg, +1);
        if (found != Integer.MIN_VALUE) return found;
        return scanBufferedDir(level, originX, tg, -1);
    }

    /**
     * Walk in {@code dir} from {@code originX} looking for a contiguous run
     * of {@code 2 * X_BUFFER + 1} non-tunnel columns. Returns the middle of
     * the first qualifying run, or {@link Integer#MIN_VALUE} if no run
     * exists in {@link #MAX_ABOVEGROUND_SCAN} blocks.
     */
    private static int scanBufferedDir(
        ServerLevel level, int originX, TunnelGeometry tg, int dir
    ) {
        int needed = 2 * X_BUFFER + 1;
        int consecutive = 0;
        int runStart = 0;
        int dxStart = (dir > 0) ? 0 : 1;
        for (int dx = dxStart; dx <= MAX_ABOVEGROUND_SCAN; dx++) {
            int x = originX + dir * dx;
            if (!TunnelGenerator.isColumnUnderground(level, x, tg)) {
                if (consecutive == 0) runStart = x;
                consecutive++;
                if (consecutive >= needed) {
                    return runStart + dir * X_BUFFER;
                }
            } else {
                consecutive = 0;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Walk +X from {@code originX} until the first non-tunnel column.
     * Falls back to −X if +X stays buried for {@link #MAX_ABOVEGROUND_SCAN}
     * blocks. Returns {@code originX} if both directions are buried.
     * Used as the look-direction fallback when no buffered window exists.
     */
    private static int findAboveGroundReferenceX(
        ServerLevel level, int originX, TunnelGeometry tg
    ) {
        if (!TunnelGenerator.isColumnUnderground(level, originX, tg)) return originX;
        for (int dx = 1; dx <= MAX_ABOVEGROUND_SCAN; dx++) {
            int forward = originX + dx;
            if (!TunnelGenerator.isColumnUnderground(level, forward, tg)) return forward;
        }
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
     * Random perpendicular offset around the X anchor. Re-rolls up to
     * {@link #MAX_ATTEMPTS} times if the candidate is in water or otherwise
     * invalid. If the random search fails (e.g. all probes hit deep ocean),
     * walks perpendicular outward looking for the first dry surface. As a
     * last resort, spawns on top of the train so the player never lands on
     * tracks AND never lands in water.
     */
    private static PlayerTarget pickPlayerTarget(
        ServerLevel level, int anchorX, TrackGeometry g, Vector3d trainCenter
    ) {
        RandomSource rand = level.getRandom();
        double centerZ = g.trackCenterZ() + 0.5;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            double xOffset = (rand.nextDouble() * 2.0 - 1.0) * X_JITTER_MAX;
            double sideSign = rand.nextBoolean() ? 1.0 : -1.0;
            double perpDist = PERP_MIN + rand.nextDouble() * (PERP_MAX - PERP_MIN);
            double zOffset = sideSign * perpDist;

            double px = anchorX + 0.5 + xOffset;
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

        // Random retry exhausted — corridor crosses deep ocean or other
        // hostile perpendicular. Walk perpendicular outward looking for the
        // first dry surface on either side. Never accept a wet/void column.
        for (int perpDist = (int) PERP_MIN; perpDist <= MAX_FALLBACK_PERP; perpDist++) {
            for (int sign : new int[] {+1, -1}) {
                double pz = centerZ + sign * perpDist;
                int bz = Mth.floor(pz);
                level.getChunk(anchorX >> 4, bz >> 4, ChunkStatus.FULL, true);
                int gy = findGroundY(level, anchorX, bz);
                int py = gy + 1;
                if (isSafePlayerPos(level, anchorX, py, bz)) {
                    LOGGER.info("[DungeonTrain] pickPlayerTarget random search exhausted; widened perp walk found dry ground at Z={} Y={}",
                        String.format("%.1f", pz), py);
                    return new PlayerTarget(anchorX + 0.5, py, pz);
                }
            }
        }

        // Tiny-island corner case: no dry land within ±MAX_FALLBACK_PERP.
        // Spawn on top of the train so the player neither drowns nor lands
        // on the rails. Y = trainCenter.y + 8 clears the carriage roof.
        double tx = trainCenter.x;
        int ty = (int) Math.ceil(trainCenter.y) + 8;
        double tz = trainCenter.z;
        LOGGER.warn("[DungeonTrain] pickPlayerTarget found no dry land within ±{} perp — last-resort spawn above train at ({}, {}, {})",
            MAX_FALLBACK_PERP,
            String.format("%.1f", tx), ty, String.format("%.1f", tz));
        return new PlayerTarget(tx, ty, tz);
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
     * against the ceiling, with water/lava at body/head level (no submerged
     * spawns), or with leaves at body/head level (the ground probe walks
     * through leaves to reach the trunk top, which can leave the player's
     * body inside the canopy).
     */
    private static boolean isSafePlayerPos(ServerLevel level, int x, int y, int z) {
        if (y < level.getMinBuildHeight() + VOID_CLEARANCE) return false;
        if (y > level.getMaxBuildHeight() - CEILING_CLEARANCE) return false;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.set(x, y, z);
        BlockState body = level.getBlockState(pos);
        if (!body.getFluidState().isEmpty()) return false;
        if (body.is(BlockTags.LEAVES)) return false;
        pos.set(x, y + 1, z);
        BlockState head = level.getBlockState(pos);
        if (!head.getFluidState().isEmpty()) return false;
        if (head.is(BlockTags.LEAVES)) return false;
        return true;
    }

    private record PlayerTarget(double px, int py, double pz) {}
}
