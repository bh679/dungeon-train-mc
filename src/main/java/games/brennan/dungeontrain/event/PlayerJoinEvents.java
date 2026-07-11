package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.debug.DebugFlags;
import games.brennan.dungeontrain.editor.EditorWelcome;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.VoidBandSyncPacket;
import games.brennan.dungeontrain.net.PrefabRegistrySyncPacket;
import games.brennan.dungeontrain.net.SpawnDeckHoldPacket;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.HitResult;
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

    /**
     * Max blocks to slide the spawn anchor along the track when the
     * original anchor produces no LOS-clear candidates. The camera still
     * points back toward the train area, so a slide simply moves the
     * player further down the tracks to a clearer vantage point. 300
     * blocks ÷ {@code 2 * X_JITTER_MAX} stride = up to 10 retries before
     * the on-train last-resort fires.
     */
    private static final int MAX_X_SLIDE = 300;

    /** Standing eye height (blocks above feet) — used for pitch math. */
    private static final double EYE_HEIGHT = 1.62;

    /** MC yaw facing +X (the train's travel direction) — used for on-train spawns. */
    private static final float FORWARD_YAW = -90.0f;

    /**
     * Player feet sit this far above the flatbed floor block top on spawn — a
     * small drop so they settle cleanly onto the deck (the centre walkway is
     * top slabs, surface at {@code trainY + 1}) rather than risk a one-tick
     * embed against it.
     */
    private static final double FLATBED_FEET_EPSILON = 0.15;

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
        // Sync the disintegration-band geometry (per-world carriage length + train
        // flag) so the client can fade the sky/fog toward the End across the band.
        DungeonTrainWorldData bandData = DungeonTrainWorldData.get(player.serverLevel().getServer().overworld());
        DungeonTrainNet.sendTo(player, new VoidBandSyncPacket(bandData.dims().length(), bandData.startsWithTrain()));
        PENDING.put(player.getUUID(), 0);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PENDING.remove(player.getUUID());
            CinematicIntroService.forget(player.getUUID());
            EditorWelcome.forget(player.getUUID());
            DevMessageConsent.forget(player.getUUID());
            NetworkConsentMirror.forget(player.getUUID());
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
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;

        MinecraftServer server = level.getServer();
        // Expire spawn-cinematic invulnerability windows — must run even when
        // no players are queued for placement.
        CinematicIntroService.tick(server);
        // Deliver any editor welcome whose 2.2s post-entry delay has elapsed.
        EditorWelcome.tick(server);

        if (PENDING.isEmpty()) return;
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

        // We now place the body ON the live train (a flatbed deck), not at the
        // ground bootstrap pose. The first-time PlayerFudgeSpawnMixin still puts
        // the client at the ground pose pre-JOIN (no origin flash); the body's
        // teleport onto the moving train then happens under the detached
        // cinematic camera and is invisible. So no cached no-op fast path — we
        // always wait for the live train and recompute from it.
        ManagedShip trainShip = findTrain(trainLevel);
        if (trainShip == null) return false; // no settled train group yet — retry next tick

        TrainTransformProvider provider =
            (TrainTransformProvider) trainShip.getKinematicDriver();
        Vector3d trainCenter = resolveTrainCenter(provider);
        if (trainCenter == null) return false; // baseline not captured yet — retry next tick

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

        // Camera start may sit over water (it's just a camera viewpoint).
        PlayerTarget target = pickPlayerTarget(trainLevel, anchorX, g, tg, trainCenter, /*allowWater*/ true);
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

        // (target, yaw, pitch) is the OLD ground spawn pose — reused as the
        // cinematic CAMERA START, not the body position. The body spawns on a
        // flatbed deck of the live train; the detached camera hides the move.
        SpawnPlacement groundPose = new SpawnPlacement(
            target.px, target.py, target.pz, yaw, pitch,
            new BlockPos(Mth.floor(target.px), target.py, Mth.floor(target.pz)));
        FlatbedTarget flat = computeFlatbedTarget(provider, data, trainCenter);

        // Face along travel (+X) — this is the view restored when the cinematic
        // releases control.
        player.teleportTo(trainLevel, flat.x(), flat.y(), flat.z(), FORWARD_YAW, 0.0f);

        // Tell the client to hold the player on the deck for a window. The
        // server can stall for seconds at spawn (eager-fill appender) while the
        // client keeps ticking and free-falls the local player off the just-
        // teleported deck onto the world bed under the train. The local player's
        // movement is client-authoritative, so the hold must run client-side.
        DungeonTrainNet.sendTo(player, new SpawnDeckHoldPacket(
            data.getTrainY() + 1.0, SpawnDeckHoldPacket.DEFAULT_HOLD_TICKS));

        boolean cinematic = CinematicIntroService.shouldPlay(player);
        LOGGER.info("[DungeonTrain] Login spawn for {}: onTrain=({}, {}, {}) camStart=({}, {}, {}) yaw={} pitch={} cinematic={} trainCenter=({}, {}, {}) anchorX={} lookX={} buffered={}",
            player.getName().getString(),
            String.format("%.1f", flat.x()), String.format("%.1f", flat.y()), String.format("%.1f", flat.z()),
            String.format("%.1f", target.px), target.py, String.format("%.1f", target.pz),
            String.format("%.1f", yaw), String.format("%.1f", pitch), cinematic,
            String.format("%.1f", trainCenter.x), String.format("%.1f", trainCenter.y), String.format("%.1f", trainCenter.z),
            anchorX, lookX, haveBuffered);

        if (cinematic) {
            // Join intro: let the client hold the cinematic behind a loading
            // screen until the terrain around the shot has streamed in.
            CinematicIntroService.play(player, groundPose, true);
        }
        return true;
    }

    /**
     * Resolve the train's world-space centre from the kinematic driver's
     * {@code canonicalPos}. Returns {@code null} when the driver has not yet
     * captured its spawn baseline (first physics tick pending).
     *
     * <p>There is no safe placeholder for the flatbed-deck maths: an assumed
     * centre (e.g. world origin) lands the player tens of blocks off the real
     * deck, into an open-air column or wedged in the slab. So callers must
     * retry (login) or fall back to the ground pose (respawn) rather than place
     * at a guessed centre. {@link #findTrain} already filters to settled
     * groups, so in practice this only returns {@code null} defensively.</p>
     */
    private static Vector3d resolveTrainCenter(TrainTransformProvider provider) {
        if (provider == null) return null;
        Vector3dc canonical = provider.getCanonicalPos();
        if (canonical == null) return null;
        return new Vector3d(canonical.x(), canonical.y(), canonical.z());
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

    /**
     * Resolve the carriage group to spawn the player on: the FRONTMOST group
     * (highest {@link TrainTransformProvider#getGroupHighestPIdx()}) whose
     * kinematic baseline has been captured ({@code getCanonicalPos() != null}).
     *
     * <p>Two reasons to pick the frontmost <em>settled</em> group rather than
     * whatever {@code Shipyards.findAll()} happens to yield first:</p>
     * <ul>
     *   <li><b>Frontmost</b> — the player lands on the very front deck of the
     *       train, consistent with the {@code -90°} (+X travel) spawn yaw. A
     *       hash-ordered {@code findAll()} would otherwise drop them on an
     *       arbitrary mid-train seam.</li>
     *   <li><b>Settled</b> — a group only has a real world-space centre once its
     *       first physics tick captures {@code canonicalPos} (see
     *       {@link TrainTransformProvider#nextTransform}). Skipping unsettled
     *       groups guarantees {@link #resolveTrainCenter} returns a true centre
     *       instead of {@code null}, which is what previously fell back to a
     *       placeholder origin and dropped players under the train / into the
     *       deck slab.</li>
     * </ul>
     *
     * <p>Returns {@code null} when no group has settled yet — callers retry next
     * tick (login) or fall back to the ground pose (respawn).</p>
     */
    private static ManagedShip findTrain(ServerLevel level) {
        ManagedShip lead = null;
        int leadPIdx = Integer.MIN_VALUE;
        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            if (ship.getKinematicDriver() instanceof TrainTransformProvider provider
                && provider.getCanonicalPos() != null
                && provider.getGroupHighestPIdx() > leadPIdx) {
                leadPIdx = provider.getGroupHighestPIdx();
                lead = ship;
            }
        }
        return lead;
    }

    /** A world-space spot to stand the player on the train (flatbed deck top). */
    public record FlatbedTarget(double x, double y, double z) {}

    /**
     * Compute a flatbed-deck spawn spot from a resolved train group.
     *
     * <p>Each carriage group's sub-level is laid out
     * {@code [BACK pad | groupSize × carriage | FRONT pad]} spanning
     * {@code groupSize·length + 2·halfPadLen} blocks along X, centred on the
     * sub-level AABB centre ({@code center}, i.e. the driver's canonical
     * position). The FRONT (+X) {@code halfPadLen} blocks are an open flatbed
     * deck; its centre X is {@code center.x + stride/2 − halfPad/2}. The deck
     * floor sits at {@code trainY} (carriage floor), so feet go just above it
     * at {@code trainY + 1}. Z is the track centre — the corridor middle.</p>
     */
    private static FlatbedTarget computeFlatbedTarget(
        TrainTransformProvider provider, DungeonTrainWorldData data, Vector3d center
    ) {
        CarriageDims dims = data.dims();
        int trainY = data.getTrainY();
        int halfPad = CarriagePlacer.halfPadLen(dims);
        int stride = provider.getGroupSize() * dims.length() + 2 * halfPad;
        double frontPadCenterX = center.x + stride / 2.0 - halfPad / 2.0;
        TrackGeometry g = TrackGeometry.from(dims, trainY);
        return new FlatbedTarget(
            frontPadCenterX,
            trainY + 1 + FLATBED_FEET_EPSILON,
            g.trackCenterZ() + 0.5);
    }

    /**
     * Resolve the train in {@code trainLevel} and compute a flatbed-deck spawn
     * spot, or {@code null} if no train has bound yet. Shared with
     * {@link RespawnDimensionEvents} so respawns also land on the train.
     */
    public static FlatbedTarget findFlatbedTarget(ServerLevel trainLevel, DungeonTrainWorldData data) {
        ManagedShip ship = findTrain(trainLevel);
        if (ship == null) return null;
        TrainTransformProvider provider = (TrainTransformProvider) ship.getKinematicDriver();
        Vector3d center = resolveTrainCenter(provider);
        if (center == null) return null;
        return computeFlatbedTarget(provider, data, center);
    }

    /**
     * Pick a spawn position with a verified line of sight to the train.
     *
     * <p>Strategy:</p>
     * <ol>
     *   <li>Try the original anchor (random jitter + perpendicular walk,
     *       both gated on {@link #hasLineOfSight}).</li>
     *   <li>If no LOS-clear candidate exists at that anchor, slide the
     *       anchor along the track in both directions by stride
     *       {@code 2 * X_JITTER_MAX} (so the random ranges don't overlap)
     *       and retry. Tunnel-buried X columns are skipped. Camera
     *       direction still points back toward the train, so the player
     *       just ends up further down the tracks looking at the train.</li>
     *   <li>Last resort: spawn on top of the train — guarantees LOS
     *       (player is literally on the train) and avoids landing on
     *       rails or in water.</li>
     * </ol>
     */
    private static PlayerTarget pickPlayerTarget(
        ServerLevel level, int anchorX, TrackGeometry g, TunnelGeometry tg,
        Vector3d trainCenter, boolean allowWater
    ) {
        RandomSource rand = level.getRandom();
        // Aim point sits in the open air ABOVE the train, well clear of:
        //   - the bed/carriage blocks (bed Y .. bed Y + ~3)
        //   - the trench wall (extends from bed Y up to local surface Y)
        // The open corridor is open to sky at above-ground X stretches, so
        // a ray to (trainX, bedY + 8, trainZ) crosses the trench top and
        // arrives in open air. If the ray hits something it's a genuine
        // overhead obstruction (tree, overhanging cliff).
        Vec3 trainAim = new Vec3(trainCenter.x, trainCenter.y + 8.0, trainCenter.z);

        // Phase A — original anchor.
        PlayerTarget r = tryFindLOSClearSpawn(level, anchorX, g, trainAim, rand, allowWater);
        if (r != null) return r;

        // Phase B — slide the anchor along the track in both directions.
        // 2 * X_JITTER_MAX stride so each slide explores a fresh X region.
        int stride = (int) (2.0 * X_JITTER_MAX);
        for (int step = stride; step <= MAX_X_SLIDE; step += stride) {
            for (int dir : new int[] {+1, -1}) {
                int newAnchor = anchorX + dir * step;
                level.getChunk(newAnchor >> 4, tg.centerZ() >> 4, ChunkStatus.FULL, true);
                if (TunnelGenerator.isColumnUnderground(level, newAnchor, tg)) continue;
                r = tryFindLOSClearSpawn(level, newAnchor, g, trainAim, rand, allowWater);
                if (r != null) {
                    LOGGER.info("[DungeonTrain] X-slide found LOS-clear anchor at X={} (slid {} from original X={})",
                        newAnchor, dir * step, anchorX);
                    return r;
                }
            }
        }

        // Phase C — true last resort: spawn on top of the train so the
        // player neither drowns nor lands on the rails, and LOS to the
        // train is trivially satisfied (they're on it).
        int tx = Mth.floor(trainCenter.x);
        int ty = (int) Math.ceil(trainCenter.y) + 8;
        int tz = Mth.floor(trainCenter.z);
        LOGGER.warn("[DungeonTrain] pickPlayerTarget exhausted X-slide ±{} — last-resort spawn above train at ({}, {}, {})",
            MAX_X_SLIDE, tx, ty, tz);
        return new PlayerTarget(tx + 0.5, ty, tz + 0.5);
    }

    /**
     * Run the random + perpendicular-walk search at a single anchor X.
     * Returns the first candidate that passes both {@link #isSafePlayerPos}
     * and {@link #hasLineOfSight} to {@code trainAim}, or {@code null} if
     * no candidate within {@link #MAX_FALLBACK_PERP} satisfies both.
     */
    private static PlayerTarget tryFindLOSClearSpawn(
        ServerLevel level, int anchorX, TrackGeometry g,
        Vec3 trainAim, RandomSource rand, boolean allowWater
    ) {
        double centerZ = g.trackCenterZ() + 0.5;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            double xOffset = (rand.nextDouble() * 2.0 - 1.0) * X_JITTER_MAX;
            double sideSign = rand.nextBoolean() ? 1.0 : -1.0;
            double perpDist = PERP_MIN + rand.nextDouble() * (PERP_MAX - PERP_MIN);
            double zOffset = sideSign * perpDist;

            int bx = Mth.floor(anchorX + 0.5 + xOffset);
            int bz = Mth.floor(centerZ + zOffset);
            level.getChunk(bx >> 4, bz >> 4, ChunkStatus.FULL, true);

            int groundY = findGroundY(level, bx, bz, allowWater);
            int playerY = groundY + 1;
            if (!isSafePlayerPos(level, bx, playerY, bz, allowWater)) continue;
            if (!hasLineOfSight(level, bx + 0.5, playerY + EYE_HEIGHT, bz + 0.5,
                                trainAim.x, trainAim.y, trainAim.z)) continue;
            return new PlayerTarget(bx + 0.5, playerY, bz + 0.5);
        }

        for (int perpDist = (int) PERP_MIN; perpDist <= MAX_FALLBACK_PERP; perpDist++) {
            for (int sign : new int[] {+1, -1}) {
                int bz = Mth.floor(centerZ + sign * perpDist);
                level.getChunk(anchorX >> 4, bz >> 4, ChunkStatus.FULL, true);
                int gy = findGroundY(level, anchorX, bz, allowWater);
                int py = gy + 1;
                if (!isSafePlayerPos(level, anchorX, py, bz, allowWater)) continue;
                if (!hasLineOfSight(level, anchorX + 0.5, py + EYE_HEIGHT, bz + 0.5,
                                    trainAim.x, trainAim.y, trainAim.z)) continue;
                return new PlayerTarget(anchorX + 0.5, py, bz + 0.5);
            }
        }

        return null;
    }

    /**
     * Compute a player spawn placement next to the train in {@code trainLevel}.
     * Pure: does not touch {@link #bootstrapPlacement} or emit log lines.
     * Uses {@code trainOrigin} (= {@code (0, trainY, trackCenterZ)}) as the
     * trainCenter — the train is freshly spawned at this point and hasn't
     * had a chance to move.
     *
     * <p>Callers that need the placement for the world bootstrap (first
     * login → mixin fudge) should call
     * {@link #computeAndCacheBootstrapPlacement} instead, which delegates
     * here and then sets the cache field. Respawn handlers reusing the same
     * placement maths must call this pure version so they don't corrupt the
     * cached value the mixin reads for first-time players.</p>
     */
    public static SpawnPlacement computeBootstrapPlacement(
        ServerLevel trainLevel, CarriageDims dims, int trainY
    ) {
        // Bootstrap: the train is freshly spawned at origin and hasn't moved yet,
        // so the trainCenter is (0, trainY, trackCenterZ). Real body placement
        // (world default spawn / fudge / respawn): never water.
        Vector3d trainCenter = new Vector3d(
            0.0, trainY, TrackGeometry.from(dims, trainY).trackCenterZ() + 0.5);
        return computeGroundPoseAt(trainLevel, dims, trainY, trainCenter, /*allowWater*/ false);
    }

    /**
     * Random nearby ground pose facing the train at the given (already-resolved)
     * {@code trainCenter}: scans for a line-of-sight-clear spawn column near the track
     * (via {@link #pickPlayerTarget}) and aims it back at a reference point on the train.
     * Shared by {@link #computeBootstrapPlacement} (origin trainCenter) and the on-demand
     * cinematic replay ({@link #computeReplaySpawnPose}, live trainCenter).
     *
     * @param allowWater camera-only starts may sit over water; real body placements must not.
     */
    private static SpawnPlacement computeGroundPoseAt(
        ServerLevel trainLevel, CarriageDims dims, int trainY, Vector3d trainCenter, boolean allowWater
    ) {
        TrackGeometry g = TrackGeometry.from(dims, trainY);
        TunnelGeometry tg = TunnelGeometry.from(g);
        int trainX = (int) Math.floor(trainCenter.x);

        int bufferedX = findBufferedReferenceX(trainLevel, trainX, tg);
        boolean haveBuffered = bufferedX != Integer.MIN_VALUE;
        int lookX = haveBuffered ? bufferedX : findAboveGroundReferenceX(trainLevel, trainX, tg);
        int anchorX = haveBuffered ? bufferedX : lookX;

        PlayerTarget pt = pickPlayerTarget(trainLevel, anchorX, g, tg, trainCenter, allowWater);
        Vec3 referencePoint = new Vec3(lookX + 0.5, g.bedY() + 1.5, g.trackCenterZ() + 0.5);

        double dx = referencePoint.x - pt.px;
        double dy = referencePoint.y - (pt.py + EYE_HEIGHT);
        double dz = referencePoint.z - pt.pz;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));

        BlockPos blockPos = new BlockPos(Mth.floor(pt.px), pt.py, Mth.floor(pt.pz));
        return new SpawnPlacement(pt.px, pt.py, pt.pz, yaw, pitch, blockPos);
    }

    /**
     * Spawn-style (random nearby ground) camera-start pose for an on-demand cinematic
     * replay ({@code /dungeontrain cinematic spawn}): a ground spot just off the side of the
     * track, <em>beside where the player currently is</em>, looking back at them. The search
     * is anchored on the player's current X (not the train's geometric centre, which on a long
     * moving train can be far away); Z/Y are deterministic from the track geometry (the train
     * runs along {@code trackCenterZ} at {@code trainY}), so no live-train lookup is needed.
     *
     * <p>Returns {@code null} — the caller's signal to fall back to the player's current view —
     * when the train dimension isn't loaded or the player isn't in it (the detached camera
     * renders in the player's own level, so a cross-dimension pose would be wrong).</p>
     */
    public static SpawnPlacement computeReplaySpawnPose(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return null;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
        ServerLevel trainLevel = server.getLevel(data.startingDimension().levelKey());
        if (trainLevel == null || player.level() != trainLevel) return null;

        // Anchor on the player's CURRENT X — the point on the track beside them. The
        // perpendicular search in computeGroundPoseAt then lands the camera just off the
        // side of the track near the player; allowWater since it's only a viewpoint.
        int trainY = data.getTrainY();
        TrackGeometry g = TrackGeometry.from(data.dims(), trainY);
        Vector3d focus = new Vector3d(player.getX(), trainY, g.trackCenterZ() + 0.5);
        return computeGroundPoseAt(trainLevel, data.dims(), trainY, focus, /*allowWater*/ true);
    }

    /**
     * Compute and cache the spawn placement that {@code TrainBootstrapEvents}
     * will pin the level's default spawn point to. The cached placement is
     * reused at the player's first {@code tryPlace} attempt so the resulting
     * teleport is a no-op for position.
     *
     * <p>The cache is also read by {@link PlayerFudgeSpawnMixin} to override
     * vanilla {@code adjustSpawnLocation} for first-time players. Respawn-time
     * placement computation must use {@link #computeBootstrapPlacement}
     * instead — it returns the same value without poisoning the cache.</p>
     */
    public static SpawnPlacement computeAndCacheBootstrapPlacement(
        ServerLevel trainLevel, CarriageDims dims, int trainY
    ) {
        SpawnPlacement placement = computeBootstrapPlacement(trainLevel, dims, trainY);
        bootstrapPlacement = placement;
        LOGGER.info("[DungeonTrain] Bootstrap placement cached: pos=({}, {}, {}) yaw={} pitch={}",
            String.format("%.1f", placement.x()), placement.y(), String.format("%.1f", placement.z()),
            String.format("%.1f", placement.yaw()), String.format("%.1f", placement.pitch()));
        return placement;
    }

    /**
     * Walk down from the world ceiling through air/fluid/leaves/vines until
     * a solid block is hit. Returns the Y of that block (ground Y); caller
     * stands the player at {@code groundY + 1}. Returns
     * {@code level.getMinBuildHeight() - 1} (sentinel: no ground found) if
     * every scanned block is passable.
     */
    private static int findGroundY(ServerLevel level, int x, int z, boolean allowWater) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        int startY = level.getMaxBuildHeight() - 1;
        for (int y = startY; y >= minY; y--) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            // Camera-only allowWater: the water surface counts as ground so the
            // camera starts just above it rather than descending to the seabed.
            if (allowWater && !state.getFluidState().isEmpty()) {
                return y;
            }
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
    private static boolean isSafePlayerPos(ServerLevel level, int x, int y, int z, boolean allowWater) {
        if (y < level.getMinBuildHeight() + VOID_CLEARANCE) return false;
        if (y > level.getMaxBuildHeight() - CEILING_CLEARANCE) return false;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.set(x, y, z);
        BlockState body = level.getBlockState(pos);
        if (!allowWater && !body.getFluidState().isEmpty()) return false;
        if (body.is(BlockTags.LEAVES)) return false;
        if (body.is(BlockTags.ICE)) return false;
        pos.set(x, y + 1, z);
        BlockState head = level.getBlockState(pos);
        if (!allowWater && !head.getFluidState().isEmpty()) return false;
        if (head.is(BlockTags.LEAVES)) return false;
        if (head.is(BlockTags.ICE)) return false;
        return true;
    }

    /**
     * Returns {@code true} if a ray from {@code (fromX,Y,Z)} to {@code (toX,Y,Z)}
     * hits no solid block. Uses {@link ClipContext.Block#COLLIDER} so
     * full-cube terrain, leaves, and ice spikes block the ray; uses
     * {@link ClipContext.Fluid#NONE} so water/lava do NOT block (the player
     * can spawn near a coastal corridor and still see the train through
     * shallow water). The train itself lives in a Sable sub-level — its
     * blocks are NOT in this level, so this ray only intersects world terrain.
     */
    private static boolean hasLineOfSight(
        ServerLevel level,
        double fromX, double fromY, double fromZ,
        double toX,   double toY,   double toZ
    ) {
        Vec3 from = new Vec3(fromX, fromY, fromZ);
        Vec3 to   = new Vec3(toX,   toY,   toZ);
        HitResult hit = level.clip(new ClipContext(
            from, to,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            (net.minecraft.world.entity.Entity) null
        ));
        return hit.getType() == HitResult.Type.MISS;
    }

    private record PlayerTarget(double px, int py, double pz) {}
}
