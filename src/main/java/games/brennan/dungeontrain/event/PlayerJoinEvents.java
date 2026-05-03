package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.debug.DebugFlags;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PrefabRegistrySyncPacket;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
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
     * a deep ocean). Tight cap so the player ends up near the tracks even
     * in extreme biomes — beyond this we prefer the on-train fallback over
     * spawning the player half a chunk away.
     */
    private static final int MAX_FALLBACK_PERP = 60;

    /** Standing eye height (blocks above feet) — used for pitch math. */
    private static final double EYE_HEIGHT = 1.62;

    /**
     * Max ticks to keep retrying the train lookup after login. 20 TPS,
     * so 100 ≈ 5 s — generous; in practice the race resolves in 1–2 ticks.
     */
    private static final int MAX_RETRY_TICKS = 100;

    /** Player UUID → tick count of pending retry. Cleared on success/timeout/logout. */
    private static final Map<UUID, Integer> PENDING = new ConcurrentHashMap<>();

    /**
     * Pre-computed placement from {@code TrainBootstrapEvents}. Read by
     * {@code PlayerFudgeSpawnMixin} to override vanilla's
     * {@code fudgeSpawnLocation} for first-time players, and read again here
     * at first-tick {@link #tryPlace} to confirm via a no-op teleport. The
     * latter is needed because the rolling-window manager and other systems
     * may have moved the train slightly between server-start and login —
     * {@link #tryPlace}'s buffered-X recompute could otherwise drift off the
     * cached placement and cause a visible jump.
     */
    private static volatile SpawnPlacement bootstrapPlacement;

    /** Read accessor for {@code PlayerFudgeSpawnMixin}. */
    public static SpawnPlacement getBootstrapPlacement() {
        return bootstrapPlacement;
    }

    /**
     * Cached spawn placement: position, look angles, and the {@link BlockPos}
     * passed to {@link ServerLevel#setDefaultSpawnPos(BlockPos, float)}.
     * The {@code blockPos} is what the level's spawn point reflects;
     * {@code x}/{@code z} are the precise sub-block coordinates aligned to
     * {@code blockPos + 0.5} so a re-teleport doesn't shift the player.
     */
    public record SpawnPlacement(double x, int y, double z, float yaw, float pitch, BlockPos blockPos) {}

    private PlayerJoinEvents() {}

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        DungeonTrainNet.sendTo(player, PrefabRegistrySyncPacket.fromRegistries());
        // Seed debug flags so the in-world Debug menu's Toggle states render
        // the correct value the first time it's opened on this client.
        DebugFlags.sendSnapshotTo(player);
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

        // First-time-like login: if the bootstrap placement is cached AND the
        // player is at (or very near) the level's default spawn = the cached
        // placement, reuse it. teleportTo to the same position is a no-op —
        // only rotation updates — so no visual position jump. This is what
        // makes "TP happens before the user sees anything" actually true on a
        // fresh-world login.
        // First-time login: PlayerFudgeSpawnMixin has already moved the
        // player to the cached placement before the JOIN packet was sent.
        // Confirm via a no-op teleport (same pos + yaw + pitch) so any
        // server-side state syncs cleanly with the client.
        SpawnPlacement boot = bootstrapPlacement;
        if (boot != null && trainLevel == player.level()) {
            double cdx = player.getX() - boot.x();
            double cdz = player.getZ() - boot.z();
            if (cdx * cdx + cdz * cdz < 4.0) {
                player.teleportTo(trainLevel, boot.x(), boot.y(), boot.z(), boot.yaw(), boot.pitch());
                LOGGER.info("[DungeonTrain] Login spawn (cached bootstrap placement) for {}: pos=({}, {}, {}) yaw={} pitch={}",
                    player.getName().getString(),
                    String.format("%.1f", boot.x()), boot.y(), String.format("%.1f", boot.z()),
                    String.format("%.1f", boot.yaw()), String.format("%.1f", boot.pitch()));
                return true;
            }
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
        // the player is spawned well past any tunnel mouth. If not found,
        // fall back to the first non-tunnel X (so the player spawns near a
        // visible stretch of track rather than on top of the buried train).
        int bufferedX = findBufferedReferenceX(trainLevel, trainX, tg);
        boolean haveBuffered = bufferedX != Integer.MIN_VALUE;
        int lookX = haveBuffered ? bufferedX : findAboveGroundReferenceX(trainLevel, trainX, tg);
        int anchorX = haveBuffered ? bufferedX : lookX;

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
     *
     * <p>Force-loads each column's chunk before checking — without this,
     * unloaded chunks (common at bootstrap time when only spawn chunks are
     * loaded) get treated as non-tunnel by {@code isColumnUnderground}'s
     * {@code hasChunkAt} early exit, which corrupts the scan.</p>
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
            level.getChunk(x >> 4, tg.centerZ() >> 4, ChunkStatus.FULL, true);
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
        level.getChunk(originX >> 4, tg.centerZ() >> 4, ChunkStatus.FULL, true);
        if (!TunnelGenerator.isColumnUnderground(level, originX, tg)) return originX;
        for (int dx = 1; dx <= MAX_ABOVEGROUND_SCAN; dx++) {
            int forward = originX + dx;
            level.getChunk(forward >> 4, tg.centerZ() >> 4, ChunkStatus.FULL, true);
            if (!TunnelGenerator.isColumnUnderground(level, forward, tg)) return forward;
        }
        for (int dx = 1; dx <= MAX_ABOVEGROUND_SCAN; dx++) {
            int backward = originX - dx;
            level.getChunk(backward >> 4, tg.centerZ() >> 4, ChunkStatus.FULL, true);
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

            int bx = Mth.floor(anchorX + 0.5 + xOffset);
            int bz = Mth.floor(centerZ + zOffset);
            level.getChunk(bx >> 4, bz >> 4, ChunkStatus.FULL, true);

            int groundY = findGroundY(level, bx, bz);
            int playerY = groundY + 1;
            if (isSafePlayerPos(level, bx, playerY, bz)) {
                // Align target to bx + 0.5 / bz + 0.5 so a BlockPos round-trip
                // (via setDefaultSpawnPos) lands the player at the same spot.
                return new PlayerTarget(bx + 0.5, playerY, bz + 0.5);
            }
        }

        // Random retry exhausted — corridor crosses deep ocean or other
        // hostile perpendicular. Walk perpendicular outward looking for the
        // first dry surface on either side. Never accept a wet/void column.
        for (int perpDist = (int) PERP_MIN; perpDist <= MAX_FALLBACK_PERP; perpDist++) {
            for (int sign : new int[] {+1, -1}) {
                int bz = Mth.floor(centerZ + sign * perpDist);
                level.getChunk(anchorX >> 4, bz >> 4, ChunkStatus.FULL, true);
                int gy = findGroundY(level, anchorX, bz);
                int py = gy + 1;
                if (isSafePlayerPos(level, anchorX, py, bz)) {
                    LOGGER.info("[DungeonTrain] pickPlayerTarget random search exhausted; widened perp walk found dry ground at Z={} Y={}",
                        bz + 0.5, py);
                    return new PlayerTarget(anchorX + 0.5, py, bz + 0.5);
                }
            }
        }

        // Tiny-island corner case: no dry land within ±MAX_FALLBACK_PERP.
        // Spawn on top of the train so the player neither drowns nor lands
        // on the rails. Y = trainCenter.y + 8 clears the carriage roof.
        int tx = Mth.floor(trainCenter.x);
        int ty = (int) Math.ceil(trainCenter.y) + 8;
        int tz = Mth.floor(trainCenter.z);
        LOGGER.warn("[DungeonTrain] pickPlayerTarget found no dry land within ±{} perp — last-resort spawn above train at ({}, {}, {})",
            MAX_FALLBACK_PERP, tx, ty, tz);
        return new PlayerTarget(tx + 0.5, ty, tz + 0.5);
    }

    /**
     * Compute and cache the spawn placement that {@code TrainBootstrapEvents}
     * will pin the level's default spawn point to. Uses {@code trainOrigin}
     * (= {@code (0, trainY, trackCenterZ)}) as the trainCenter — the train is
     * freshly spawned at this point and hasn't had a chance to move. The
     * cached placement is reused at the player's first {@code tryPlace}
     * attempt so the resulting teleport is a no-op for position.
     *
     * <p>Returns {@code null} if the placement falls back to the on-train
     * last-resort branch — in that case there's no useful sharedSpawnPos
     * to set (the player would spawn on the train roof, which the retry
     * teleport at first login can handle just as well).</p>
     */
    public static SpawnPlacement computeAndCacheBootstrapPlacement(
        ServerLevel trainLevel, CarriageDims dims, int trainY
    ) {
        TrackGeometry g = TrackGeometry.from(dims, trainY);
        TunnelGeometry tg = TunnelGeometry.from(g);
        Vector3d trainCenter = new Vector3d(0.0, trainY, g.trackCenterZ() + 0.5);
        int trainX = 0;

        int bufferedX = findBufferedReferenceX(trainLevel, trainX, tg);
        boolean haveBuffered = bufferedX != Integer.MIN_VALUE;
        int lookX = haveBuffered ? bufferedX : findAboveGroundReferenceX(trainLevel, trainX, tg);
        int anchorX = haveBuffered ? bufferedX : lookX;

        PlayerTarget pt = pickPlayerTarget(trainLevel, anchorX, g, trainCenter);
        Vec3 referencePoint = new Vec3(lookX + 0.5, g.bedY() + 1.5, g.trackCenterZ() + 0.5);

        double dx = referencePoint.x - pt.px;
        double dy = referencePoint.y - (pt.py + EYE_HEIGHT);
        double dz = referencePoint.z - pt.pz;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));

        BlockPos blockPos = new BlockPos(Mth.floor(pt.px), pt.py, Mth.floor(pt.pz));
        SpawnPlacement placement = new SpawnPlacement(pt.px, pt.py, pt.pz, yaw, pitch, blockPos);
        bootstrapPlacement = placement;

        LOGGER.info("[DungeonTrain] Bootstrap placement cached: pos=({}, {}, {}) yaw={} pitch={} bufferedX={} lookX={}",
            String.format("%.1f", pt.px), pt.py, String.format("%.1f", pt.pz),
            String.format("%.1f", yaw), String.format("%.1f", pitch),
            haveBuffered ? bufferedX : "fallback", lookX);
        return placement;
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
     * treats air, fluids (water/lava), leaves, vines, the ice family,
     * and replaceable surface adornments (snow layers, tall grass, flowers,
     * etc.) as "keep descending" — any other block counts as ground.
     */
    private static boolean isPassable(BlockState state) {
        return state.isAir()
            || !state.getFluidState().isEmpty()
            || state.is(BlockTags.LEAVES)
            || state.is(Blocks.VINE)
            || state.is(BlockTags.ICE)
            || state.is(BlockTags.REPLACEABLE);
    }

    /**
     * Validates a candidate player position. Rejects positions in the void,
     * against the ceiling, with water/lava at body/head level (no submerged
     * spawns), with leaves at body/head level (the ground probe walks through
     * leaves to reach the trunk top, which can leave the player's body inside
     * the canopy), or with ice at body/head level (the ground probe also
     * descends through {@code BlockTags.ICE} so pillars and probes find the
     * true seabed under frozen oceans — without this guard, the analogue for
     * ice spikes lands the player at snow-Y but inside a tall ice column).
     */
    private static boolean isSafePlayerPos(ServerLevel level, int x, int y, int z) {
        if (y < level.getMinBuildHeight() + VOID_CLEARANCE) return false;
        if (y > level.getMaxBuildHeight() - CEILING_CLEARANCE) return false;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.set(x, y, z);
        BlockState body = level.getBlockState(pos);
        if (!body.getFluidState().isEmpty()) return false;
        if (body.is(BlockTags.LEAVES)) return false;
        if (body.is(BlockTags.ICE)) return false;
        pos.set(x, y + 1, z);
        BlockState head = level.getBlockState(pos);
        if (!head.getFluidState().isEmpty()) return false;
        if (head.is(BlockTags.LEAVES)) return false;
        if (head.is(BlockTags.ICE)) return false;
        return true;
    }

    private record PlayerTarget(double px, int py, double pz) {}
}
