package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.CarriageIndexPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tick append-only group spawner. For each train (collection of
 * groups sharing a {@link TrainTransformProvider#getTrainId() trainId}),
 * extends the train by spawning new groups at the appropriate world
 * position when a player's needed pIdx window
 * {@code [pIdx − halfBack, pIdx + halfFront]} extends beyond the train's
 * current min/max pIdx.
 *
 * <p>Each spawned group is its own Sable sub-level holding {@code groupSize}
 * adjacent carriages — see
 * {@link TrainAssembler#spawnGroup(ServerLevel, BlockPos, Vector3dc, int, int, CarriageDims, UUID)}.
 * The group's blocks are placed once at assembly and never modified after,
 * so the per-sub-level MassTracker / rotationPoint stays constant.</p>
 *
 * <p>Append-only: never erases. Walking back over a previously-spawned
 * group shows it intact.</p>
 */
public final class TrainCarriageAppender {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double NEAR_RADIUS = 128.0;
    private static final double NEAR_RADIUS_SQ = NEAR_RADIUS * NEAR_RADIUS;

    /**
     * Per-player last carriage index pushed to the client via
     * {@link CarriageIndexPacket}. Server-thread only.
     */
    private static final Map<UUID, Integer> LAST_SENT_PIDX = new HashMap<>();

    /**
     * Per-train, per-direction: the most recently spawned {@link ManagedShip}
     * for that direction. Read by the wait-for-placement-success gate in
     * {@link #updateTrain} — auto-spawn for a given direction defers until
     * THAT direction's last ship's
     * {@link TrainTransformProvider#isPlacedSuccessfully} flips true.
     *
     * <p>Split per-direction so the two ends of the train spawn
     * INDEPENDENTLY: a still-settling carriage at the lead end no longer
     * blocks the next spawn at the tail end, and vice versa. This makes the
     * forward and backward spawn lanes effectively two copies of the same
     * pipeline running in parallel.</p>
     */
    private static final Map<UUID, ManagedShip> LAST_SPAWNED_SHIP_FORWARD = new ConcurrentHashMap<>();
    private static final Map<UUID, ManagedShip> LAST_SPAWNED_SHIP_BACKWARD = new ConcurrentHashMap<>();

    /**
     * Per-train, per-direction: {@code level.getGameTime()} of the most
     * recent spawn in that direction. Diagnostic-only (the placement-success
     * gate doesn't read this); kept populated for log/debug correlation when
     * investigating spawn-cadence questions on either end.
     */
    private static final Map<UUID, Long> LAST_SPAWNED_TICK_FORWARD = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_SPAWNED_TICK_BACKWARD = new ConcurrentHashMap<>();


    /**
     * Minimum visible gap (in blocks) between a freshly-spawned group and
     * its reference. Sable's collision broad-phase considers narrow gaps
     * as contact and reacts violently — at 0.1 blocks we observed groups
     * that landed near the floor jittering by 10-20 blocks per physics
     * tick, with overlapping blocks getting destroyed (smoke particles)
     * and the emptied sub-level disposed by Sable. 0.3 leaves enough
     * slack for the broad-phase even when reference-frame drift produces
     * sub-1-block stride errors. Visible gap range becomes [0.3, 1.3]
     * blocks at every group seam — a slight aesthetic cost vs prior 0.1.
     */
    private static final double MIN_GAP_BLOCKS = 0.3;

    /**
     * Maximum iterations of pre-spawn AABB-vs-AABB collision shifting in
     * {@link #adjustForCollisions}. The appender only ever spawns at train
     * ends, so realistically one or two iterations is enough to clear the
     * lead/tail group; this cap protects against pathological topologies
     * (e.g. a future debug mode that tries to spawn into a fully-packed
     * window) without risking an infinite loop.
     */
    private static final int COLLISION_ADJUST_SAFETY_LIMIT = 16;

    /**
     * When {@code true}, the appender skips its automatic spawn loop on
     * {@link #onLevelTick}; spawns happen only via
     * {@link #requestManualSpawn()} (one J-press = one spawn cycle = up to
     * {@link #MAX_SPAWNS_PER_TICK} per train). HUD updates and planned-spawn
     * broadcasting still run every tick so the wireframe preview stays
     * fresh as the reference carriage drifts.
     *
     * <p>Default {@code false} (auto mode). Toggled via the in-world Debug
     * menu (and underlying {@code /dungeontrain debug spawnmode} command).</p>
     */
    public static volatile boolean MANUAL_MODE = false;

    /**
     * One-shot consumed by the next {@link #onLevelTick}. Set by the
     * J-keybind packet handler via {@link #requestManualSpawn()}.
     */
    private static volatile boolean MANUAL_SPAWN_REQUESTED = false;

    /**
     * Snapshot of the next planned-spawn placement per train, refreshed
     * every appender tick whether or not we actually spawn. Read by
     * {@link games.brennan.dungeontrain.event.CarriageGroupGapTicker} to
     * broadcast to clients for the wireframe preview overlay.
     *
     * <p>One entry per train with at least one anchor queued for spawning;
     * trains that are fully populated relative to nearby players don't
     * appear in the map.</p>
     */
    public record PlannedSpawn(
        UUID trainId,
        UUID referenceShipId,
        BlockPos worldOrigin,
        int sizeX,
        int sizeY,
        int sizeZ,
        int newAnchor
    ) {}

    private static final Map<UUID, PlannedSpawn> NEXT_PLANNED_SPAWNS_FORWARD = new ConcurrentHashMap<>();
    private static final Map<UUID, PlannedSpawn> NEXT_PLANNED_SPAWNS_BACKWARD = new ConcurrentHashMap<>();

    /**
     * Snapshot of every planned next-spawn across both directions. With the
     * forward and backward spawn lanes running independently, a single train
     * can have two simultaneous previews (one at each end), so this returns
     * a flat list rather than a per-train map. The wireframe overlay just
     * iterates and draws each entry.
     */
    public static List<PlannedSpawn> snapshotPlannedSpawns() {
        List<PlannedSpawn> out = new ArrayList<>(
            NEXT_PLANNED_SPAWNS_FORWARD.size() + NEXT_PLANNED_SPAWNS_BACKWARD.size());
        out.addAll(NEXT_PLANNED_SPAWNS_FORWARD.values());
        out.addAll(NEXT_PLANNED_SPAWNS_BACKWARD.values());
        return out;
    }

    /**
     * Post-spawn diagnostic check region. After every successful
     * {@link #spawnNewGroup} we run an AABB-vs-AABB intersection between
     * a fixed-size 1×3×5 box anchored at the new sub-level's first block
     * (world-space lowest-X corner) and every other carriage of the same
     * train. The result is recorded here for the wireframe overlay so
     * we can SEE — not just measure — when the previous carriage's blocks
     * have crept into the new spawn's footprint.
     *
     * <p>Anchored to the new ship's sub-level pose so the overlay rides
     * with the train. Cleared on next spawn (per-train) or
     * {@link #clearSettleTracker} on server stop / train wipe.</p>
     */
    public record SpawnCollisionCheck(
        UUID trainId,
        UUID newShipId,
        int selfPIdx,
        long ticksSinceSpawn,
        BlockPos shipyardOrigin,
        int sizeX,
        int sizeY,
        int sizeZ,
        boolean colliding,
        int collidingPIdx
    ) {}

    private static final Map<UUID, SpawnCollisionCheck> LAST_SPAWN_COLLISION_CHECK = new ConcurrentHashMap<>();

    /**
     * 1-block-thick check region size on X (the spawn-direction axis):
     * we're asking "does the previous carriage occupy the new carriage's
     * very first slice?" — a positive answer means the placement-math
     * gap was eaten and the two AABBs are touching or overlapping.
     */
    private static final int COLLISION_CHECK_SIZE_X = 1;
    /**
     * 3-block check height on Y. The carriage's interior is 5 tall, but
     * 3 catches the floor + first 2 wall courses which is where a
     * back-pad overlap would land. Configurable via this constant if
     * empirical testing wants more / less coverage.
     */
    private static final int COLLISION_CHECK_SIZE_Y = 3;
    /**
     * 5-block check width on Z — full track width. The carriage spans
     * the full Z range so anything overlapping at all on Z is caught.
     */
    private static final int COLLISION_CHECK_SIZE_Z = 5;

    /**
     * Consecutive clean (collision-free) game ticks a carriage must run
     * before {@link TrainTransformProvider#markPlacedSuccessfully} fires.
     * Once placed, the carriage is permanently exempt from the per-tick
     * collision tracker and the wireframe overlay disappears for it.
     * 60 ticks ≈ 3 s at 20 Hz, comfortably past any spawn-time AABB
     * settle latency.
     */
    private static final int CLEAN_TICKS_FOR_SUCCESS = 60;
    /**
     * Per-collision shift distance in the spawn (+X) direction. The
     * carriage's {@code spawnWorldPos} is bumped by this amount each
     * tick it's seen colliding, which the deterministic position
     * formula in {@link TrainTransformProvider#nextTransform} picks up
     * on the next physics tick — the carriage visibly hops forward
     * 0.5 blocks, away from the offending sibling. Counter resets to
     * 0 on every shift so the 60-tick clean run starts fresh.
     */
    private static final double COLLISION_SHIFT_BLOCKS = 0.5;

    /** Snapshot of the most recent post-spawn collision check per train. */
    public static Map<UUID, SpawnCollisionCheck> snapshotSpawnCollisionChecks() {
        return new HashMap<>(LAST_SPAWN_COLLISION_CHECK);
    }

    /**
     * Live per-carriage collision check across every loaded train —
     * NOT just the most recent spawn. Used during testing so the wireframe
     * overlay shows green/red at the back of EVERY group simultaneously,
     * which makes overlap regressions easier to spot when scanning the
     * train end-to-end.
     *
     * <p>Cheap: one 1×3×5 AABB-vs-AABB check per carriage against (visible
     * ∪ registry), deduped by ship id and self-skipped. Even at ~45 groups
     * per train that's well under 2k integer compares per broadcast tick.</p>
     *
     * <p>To revert to the original "most recent spawn only" behaviour,
     * point {@code CarriageGroupGapTicker} back at
     * {@link #snapshotSpawnCollisionChecks} — the post-spawn write path
     * is still wired and kept the per-train map populated.</p>
     */
    public static List<SpawnCollisionCheck> computeAllCarriageCollisionChecks(ServerLevel level) {
        long now = level.getGameTime();
        Map<UUID, List<Trains.Carriage>> trains = Trains.byTrainId(level);
        List<SpawnCollisionCheck> out = new ArrayList<>();
        for (Map.Entry<UUID, List<Trains.Carriage>> entry : trains.entrySet()) {
            UUID trainId = entry.getKey();
            List<Trains.Carriage> train = entry.getValue();
            for (Trains.Carriage carriage : train) {
                if (carriage.provider().isPlacedSuccessfully()) continue;
                SpawnCollisionCheck check = checkOneCarriage(trainId, carriage, train, now);
                if (check != null) out.add(check);
            }
        }
        return out;
    }

    /**
     * Per-tick collision-resolution pass. For every not-yet-placed
     * carriage:
     * <ul>
     *   <li><b>Colliding</b>: nudge its {@code spawnWorldPos} forward by
     *       {@link #COLLISION_SHIFT_BLOCKS}, reset clean-tick counter to 0.</li>
     *   <li><b>Clear</b>: increment clean-tick counter; if it reaches
     *       {@link #CLEAN_TICKS_FOR_SUCCESS}, mark the carriage
     *       {@code placedSuccessfully} — the wireframe overlay drops
     *       it on the next broadcast and the tracker stops touching
     *       it for the rest of the session.</li>
     * </ul>
     * Called from {@link #onLevelTick} every game tick (not gated by
     * the broadcast period) so counters are accurate to the tick.
     */
    public static void runPlacementCollisionTracker(ServerLevel level) {
        long now = level.getGameTime();
        Map<UUID, List<Trains.Carriage>> trains = Trains.byTrainId(level);
        for (Map.Entry<UUID, List<Trains.Carriage>> entry : trains.entrySet()) {
            UUID trainId = entry.getKey();
            List<Trains.Carriage> train = entry.getValue();
            for (Trains.Carriage carriage : train) {
                TrainTransformProvider provider = carriage.provider();
                if (provider.isPlacedSuccessfully()) continue;

                SpawnCollisionCheck check = checkOneCarriage(trainId, carriage, train, now);
                if (check == null) continue;

                if (check.colliding()) {
                    // Direction-aware shift: push self AWAY from the
                    // offender. Offender at higher pIdx (forward of us)
                    // → shift -X; offender at lower pIdx (behind us)
                    // → shift +X. Equal pIdx is impossible (collision
                    // check skips self), so the ternary is exhaustive.
                    double dx = (check.collidingPIdx() > check.selfPIdx())
                        ? -COLLISION_SHIFT_BLOCKS
                        : +COLLISION_SHIFT_BLOCKS;
                    provider.shiftSpawnPosition(dx, 0.0, 0.0);
                    provider.resetConsecutiveCleanTicks();
                    LOGGER.info("[DungeonTrain] Placement tracker: pIdx={} colliding (overlaps pIdx={}) — shifted {} X, timer reset",
                        provider.getPIdx(), check.collidingPIdx(),
                        String.format("%+.1f", dx));
                } else {
                    provider.incrementConsecutiveCleanTicks();
                    if (provider.getConsecutiveCleanTicks() >= CLEAN_TICKS_FOR_SUCCESS) {
                        provider.markPlacedSuccessfully();
                        LOGGER.info("[DungeonTrain] Placement tracker: pIdx={} placed successfully after {} clean ticks (ticksSinceSpawn={})",
                            provider.getPIdx(), CLEAN_TICKS_FOR_SUCCESS, check.ticksSinceSpawn());
                    }
                }
            }
        }
    }

    /**
     * Per-carriage helper for {@link #computeAllCarriageCollisionChecks}.
     * Returns {@code null} for carriages that aren't Dungeon Train carriages
     * (no provider) — defensive, the map iteration shouldn't surface those
     * but caller filters anyway.
     */
    private static SpawnCollisionCheck checkOneCarriage(
        UUID trainId,
        Trains.Carriage carriage,
        List<Trains.Carriage> train,
        long currentGameTick
    ) {
        TrainTransformProvider provider = carriage.provider();
        BlockPos shipyardOrigin = provider.getShipyardOrigin();
        int anchorPIdx = provider.getPIdx();
        long spawnTick = provider.getSpawnGameTick();
        long ticksSinceSpawn = (spawnTick < 0) ? 0L : (currentGameTick - spawnTick);

        // Position the 1×3×5 check box at whichever end of the carriage
        // faces the existing train. Forward spawn (default): the LOW-X
        // corner — the previous-pIdx sibling sits at lower X and could
        // bleed into self's first slice. Backward spawn: the HIGH-X
        // corner — the next-pIdx sibling sits at higher X and could
        // bleed into self's last slice. Stride matches the
        // {@link TrainAssembler#spawnGroup} layout: groupSize×length +
        // 2×halfPadLen for groupSize > 1, just length for groupSize == 1.
        int groupSize = provider.getGroupSize();
        CarriageDims pdims = provider.dims();
        int halfPadLen = CarriagePlacer.halfPadLen(pdims);
        int subLevelStride = (groupSize > 1)
            ? (groupSize * pdims.length() + 2 * halfPadLen)
            : pdims.length();
        int boxLocalOriginX = provider.isSpawnedBackward()
            ? (shipyardOrigin.getX() + subLevelStride - COLLISION_CHECK_SIZE_X)
            : shipyardOrigin.getX();

        Vector3d corner = new Vector3d(
            boxLocalOriginX, shipyardOrigin.getY(), shipyardOrigin.getZ());
        carriage.ship().shipToWorld(corner);
        double minX = corner.x;
        double minY = corner.y;
        double minZ = corner.z;
        double maxX = minX + COLLISION_CHECK_SIZE_X;
        double maxY = minY + COLLISION_CHECK_SIZE_Y;
        double maxZ = minZ + COLLISION_CHECK_SIZE_Z;

        long selfId = carriage.ship().id();
        boolean colliding = false;
        int collidingPIdx = 0;
        Set<Long> seen = new HashSet<>();
        seen.add(selfId);

        for (Trains.Carriage other : train) {
            if (!seen.add(other.ship().id())) continue;
            AABBdc aabb = other.ship().worldAABB();
            if (isZeroAabb(aabb)) continue;
            if (maxX > aabb.minX() && minX < aabb.maxX()
                && maxY > aabb.minY() && minY < aabb.maxY()
                && maxZ > aabb.minZ() && minZ < aabb.maxZ()) {
                colliding = true;
                collidingPIdx = other.provider().getPIdx();
                break;
            }
        }
        if (!colliding) {
            Map<Integer, ManagedShip> registry = Trains.knownGroups(trainId);
            for (Map.Entry<Integer, ManagedShip> e : registry.entrySet()) {
                ManagedShip ship = e.getValue();
                if (!seen.add(ship.id())) continue;
                AABBdc aabb = ship.worldAABB();
                if (isZeroAabb(aabb)) continue;
                if (maxX > aabb.minX() && minX < aabb.maxX()
                    && maxY > aabb.minY() && minY < aabb.maxY()
                    && maxZ > aabb.minZ() && minZ < aabb.maxZ()) {
                    colliding = true;
                    collidingPIdx = e.getKey();
                    break;
                }
            }
        }

        BlockPos boxShipyardOrigin = new BlockPos(
            boxLocalOriginX, shipyardOrigin.getY(), shipyardOrigin.getZ());
        return new SpawnCollisionCheck(
            trainId,
            carriage.ship().subLevelId(),
            anchorPIdx,
            ticksSinceSpawn,
            boxShipyardOrigin,
            COLLISION_CHECK_SIZE_X,
            COLLISION_CHECK_SIZE_Y,
            COLLISION_CHECK_SIZE_Z,
            colliding,
            colliding ? collidingPIdx : 0);
    }

    /** Trigger one spawn cycle on the next {@link #onLevelTick}. Server thread only. */
    public static void requestManualSpawn() {
        MANUAL_SPAWN_REQUESTED = true;
    }

    /**
     * Clear the wait-for-Sable-settle tracker. Wired alongside
     * {@link Trains#clearRegistry()} on server stop / train wipe so a
     * stale ship reference from a previous session doesn't gate the
     * first spawn after a fresh start.
     */
    public static void clearSettleTracker() {
        LAST_SPAWNED_SHIP_FORWARD.clear();
        LAST_SPAWNED_SHIP_BACKWARD.clear();
        LAST_SPAWNED_TICK_FORWARD.clear();
        LAST_SPAWNED_TICK_BACKWARD.clear();
        LAST_SPAWN_COLLISION_CHECK.clear();
    }

    /**
     * Hard upper bound on how many GROUPS the appender will spawn in a
     * single server tick. Set to 2 — one per direction. Forward and
     * backward spawn lanes run independently (separate
     * {@code LAST_SPAWNED_SHIP_*} gates and separate placement-success
     * waits), so a forward spawn at the +X end and a backward spawn at
     * the −X end in the same tick don't race each other: they touch
     * different reference carriages and different sub-level neighbours,
     * and Sable's async {@link dev.ryanhcode.sable.api.sublevel.SubLevelContainer#getAllSubLevels}
     * lag is irrelevant because each direction's collision check consults
     * {@link Trains#knownGroups} (the spawn-time registry, not the visible
     * train) for sibling AABBs.
     *
     * <p>Within a single direction, the per-direction
     * {@code LAST_SPAWNED_SHIP_*} gate enforces the "one in flight at a
     * time" constraint that the previous {@code MAX_SPAWNS_PER_TICK = 1}
     * was approximating, so the Sable-lag protection is preserved.</p>
     *
     * <p>The {@link Trains#knownAnchors} registry remains the
     * source of truth for "what anchors does this train own" — even with
     * the throttle, any duplicate the appender accidentally requests is
     * deduped against the registry. The throttle is the architectural
     * fix; the registry is the safety net.</p>
     *
     * <p>Throughput cost: at groupSize=3, this caps carriages added per
     * tick at 6 (3 per direction × 2 directions). The seed group from
     * {@link TrainAssembler#spawnTrain} plus the appender's first ~15
     * ticks fully populate a typical auto-rd window (~14 groups at
     * render distance 12) in &lt;1 second per side — imperceptible.</p>
     */
    private static final int MAX_SPAWNS_PER_TICK = 2;

    private TrainCarriageAppender() {}

    public static void onLevelTick(ServerLevel level) {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        // Per-tick placement collision tracker — runs every game tick
        // (not gated by the broadcast period) so the 60-tick clean-run
        // counter is precise. Mutates per-carriage state on
        // TrainTransformProvider; once a carriage transitions to
        // {@code placedSuccessfully = true} it's permanently exempt
        // from the tracker AND the wireframe overlay.
        runPlacementCollisionTracker(level);

        // Manual mode: spawn cycles fire only when a J-press has set
        // MANUAL_SPAWN_REQUESTED. The flag is NOT consumed at the top of
        // the tick — it's consumed only after a spawn actually happens
        // (see below). This matters because {@link #updateTrain} can
        // bail before its spawn loop runs (Sable visible-list lag, empty
        // anchorsToSpawn after dedup, etc.); if we consumed the flag
        // up-front, J presses that landed during a Sable-lag window
        // would be silently dropped.
        boolean spawnAllowedThisTick = !MANUAL_MODE || MANUAL_SPAWN_REQUESTED;

        Set<UUID> seenThisTick = new HashSet<>();
        Set<UUID> trainsTouchedThisTick = new HashSet<>();
        Map<UUID, List<Trains.Carriage>> trainsById = Trains.byTrainId(level);
        boolean anySpawnFired = false;
        for (List<Trains.Carriage> train : trainsById.values()) {
            if (updateTrain(level, train, players, seenThisTick, trainsTouchedThisTick, spawnAllowedThisTick)) {
                anySpawnFired = true;
            }
        }
        // Consume the manual-spawn request only if a spawn actually
        // happened. Otherwise the request stays queued for the next tick
        // (next chance to clear Sable lag, etc.) so J presses can't be
        // silently lost.
        if (MANUAL_MODE && MANUAL_SPAWN_REQUESTED && anySpawnFired) {
            MANUAL_SPAWN_REQUESTED = false;
        }
        // Drop planned-spawn entries for trains we didn't see this tick (no
        // queued anchor → wireframe should disappear). Trains we DID see
        // wrote into NEXT_PLANNED_SPAWNS_* or removed themselves from those
        // maps. Both directions are pruned in lock-step.
        NEXT_PLANNED_SPAWNS_FORWARD.keySet().retainAll(trainsTouchedThisTick);
        NEXT_PLANNED_SPAWNS_BACKWARD.keySet().retainAll(trainsTouchedThisTick);
        clearDropouts(level, seenThisTick);
    }

    /**
     * @return {@code true} iff at least one new group was spawned during this
     *     call. Used by {@link #onLevelTick} to know whether to consume
     *     {@link #MANUAL_SPAWN_REQUESTED} — bail-outs (Sable lag, empty
     *     queue, no near players) all return {@code false} so a queued
     *     J-press persists across ticks until it can fire.
     */
    private static boolean updateTrain(
        ServerLevel level,
        List<Trains.Carriage> train,
        List<ServerPlayer> players,
        Set<UUID> seenThisTick,
        Set<UUID> trainsTouchedThisTick,
        boolean spawnAllowedThisTick
    ) {
        if (train.isEmpty()) return false;
        // Any group of the train can opt the whole train out (debug probes).
        for (Trains.Carriage c : train) {
            if (c.provider().isAppenderDisabled()) return false;
        }

        Trains.Carriage lead = Trains.lead(train);
        Trains.Carriage tail = Trains.tail(train);
        TrainTransformProvider leadProvider = lead.provider();
        ManagedShip leadShip = lead.ship();
        UUID trainId = leadProvider.getTrainId();
        CarriageDims dims = leadProvider.dims();
        Vector3dc velocity = leadProvider.getTargetVelocity();
        BlockPos leadShipyardOrigin = leadProvider.getShipyardOrigin();
        int leadAnchorPIdx = leadProvider.getPIdx();
        int groupSize = leadProvider.getGroupSize();
        int length = dims.length();

        // Mark the train as touched up-front so any subsequent early-return
        // (no near players in auto mode, empty anchors after dedup, Sable lag
        // deferral, etc.) does NOT cause its NEXT_PLANNED_SPAWNS_FORWARD /
        // _BACKWARD entries to be wiped at the end of {@link #onLevelTick}.
        // Only trains that aren't loaded at all should fall out of the
        // preview broadcast.
        trainsTouchedThisTick.add(trainId);

        // Target carriage count: per-player, derived from config or each
        // player's render distance when the config is set to 0 (auto). The
        // global needed-pIdx range is the union of per-player ranges, so
        // players with different rd settings each contribute their own
        // contribution to the eventual train length.
        int configCount = DungeonTrainConfig.getNumCarriages();
        int globalMaxNeededPIdx = Integer.MIN_VALUE;
        int globalMinNeededPIdx = Integer.MAX_VALUE;

        List<Integer> nearPlayerPIdxs = new ArrayList<>();
        for (ServerPlayer player : players) {
            // Player is "near" the train if within NEAR_RADIUS of any group's
            // world AABB.
            boolean near = false;
            for (Trains.Carriage c : train) {
                AABBdc aabb = c.ship().worldAABB();
                double px = player.getX();
                double py = player.getY();
                double pz = player.getZ();
                double cdx = Math.max(0, Math.max(aabb.minX() - px, px - aabb.maxX()));
                double cdy = Math.max(0, Math.max(aabb.minY() - py, py - aabb.maxY()));
                double cdz = Math.max(0, Math.max(aabb.minZ() - pz, pz - aabb.maxZ()));
                if (cdx * cdx + cdy * cdy + cdz * cdz <= NEAR_RADIUS_SQ) {
                    near = true;
                    break;
                }
            }
            if (!near) continue;

            // Player's absolute carriage pIdx via the lead group's frame.
            // The lead group's shipyardOrigin sits at the BACK PAD's
            // lowest-X corner (groupSize > 1) or at the anchor carriage's
            // lowest-X corner (groupSize == 1). The anchor enclosed
            // carriage starts at shipyardOrigin + enclosedStartOffset,
            // where enclosedStartOffset = halfPadLen for groupSize > 1
            // and 0 for groupSize == 1. Subtract this offset before
            // dividing by length so pIdx 0's enclosed carriage maps to
            // (local.x − shipyardOrigin − enclosedStartOffset) ∈ [0, length).
            int halfPadLen = CarriagePlacer.halfPadLen(dims);
            int enclosedStartOffset = (groupSize > 1) ? halfPadLen : 0;
            Vector3d local = new Vector3d(player.getX(), player.getY(), player.getZ());
            leadShip.worldToShip(local);
            int pIdx = (int) Math.floor(
                (local.x - leadShipyardOrigin.getX() - enclosedStartOffset) / (double) length
            ) + leadAnchorPIdx;

            UUID uuid = player.getUUID();
            seenThisTick.add(uuid);
            Integer lastSent = LAST_SENT_PIDX.get(uuid);
            if (lastSent == null || lastSent != pIdx) {
                DungeonTrainNet.sendTo(player, new CarriageIndexPacket(true, pIdx));
                LAST_SENT_PIDX.put(uuid, pIdx);
            }

            int pTargetCount = (configCount > 0)
                ? configCount
                : autoTargetFromRenderDistance(player, length);
            int pHalfBack = (pTargetCount - 1) / 2;
            int pHalfFront = pTargetCount - pHalfBack - 1;
            int pMaxNeeded = pIdx + pHalfFront;
            int pMinNeeded = pIdx - pHalfBack;
            if (pMaxNeeded > globalMaxNeededPIdx) globalMaxNeededPIdx = pMaxNeeded;
            if (pMinNeeded < globalMinNeededPIdx) globalMinNeededPIdx = pMinNeeded;

            nearPlayerPIdxs.add(pIdx);
        }
        // Auto mode bails when no player is near the train (no spawning
        // and no preview broadcast are needed). Manual mode skips this
        // bailout: the wireframe preview should stay visible regardless
        // of how far the player wanders from the train, so we keep
        // updating NEXT_PLANNED_SPAWNS_FORWARD and let J fire spawns
        // even after the player has walked past the train front.
        if (!MANUAL_MODE && nearPlayerPIdxs.isEmpty()) return false;

        // Use the spawn-time registry (not Sable's visible train) to
        // determine the train's anchor range. Sable's
        // SubLevelContainer.getAllSubLevels() is asynchronous after
        // assembly — bootstrap-spawned sub-levels can take several ticks
        // to appear in the visible train, during which `lead`/`tail`
        // computed from the visible list misrepresent the actual range
        // (often returning just one or two of the four bootstrap groups).
        // Without this guard, the appender requests anchors that already
        // exist and stacks duplicate sub-levels at the same world position.
        Set<Integer> knownAnchors = Trains.knownAnchors(trainId);
        int trainMaxAnchor;
        int trainMinAnchor;
        if (knownAnchors.isEmpty()) {
            // Defensive — should never happen since the visible train has
            // at least one carriage and the spawn path always registers.
            trainMaxAnchor = leadAnchorPIdx;
            trainMinAnchor = tail.provider().getPIdx();
        } else {
            int maxA = Integer.MIN_VALUE;
            int minA = Integer.MAX_VALUE;
            for (int a : knownAnchors) {
                if (a > maxA) maxA = a;
                if (a < minA) minA = a;
            }
            trainMaxAnchor = maxA;
            trainMinAnchor = minA;
        }

        // Independent per-direction spawn decision. Forward and backward
        // are evaluated as two SEPARATE spawn lanes — each has its own
        // preview slot ({@code NEXT_PLANNED_SPAWNS_FORWARD/BACKWARD}),
        // its own placement-success gate ({@code LAST_SPAWNED_SHIP_FORWARD
        // / _BACKWARD}), and its own per-spawn bookkeeping. A still-
        // settling forward carriage no longer blocks the next backward
        // spawn (or vice versa); both directions can fire in the same
        // tick when both ends of the train need extension.
        //
        // Forward semantics preserved from the prior single-lane code:
        // we extend forward whenever any near player exists (the
        // {@code nearPlayerPIdxs.isEmpty()} early-return above already
        // filtered out the no-near-players case for auto mode). Manual
        // J always falls through to forward for press-contract continuity
        // (J = "spawn one carriage in front") — backward is auto-only.
        //
        // Backward fires only when the lowest needed pIdx falls below
        // the registry's current min anchor (i.e. a player has walked
        // off the tail).
        boolean needsForward = true;
        boolean needsBackward = !MANUAL_MODE
            && globalMinNeededPIdx != Integer.MAX_VALUE
            && globalMinNeededPIdx < trainMinAnchor;

        int forwardAnchor = trainMaxAnchor + groupSize;
        int backwardAnchor = trainMinAnchor - groupSize;

        // Belt-and-braces: even though trainMin/Max came from the
        // registry, drop any anchor that's already known. Protects against
        // races and future logic changes. Done per-direction so the other
        // direction can still proceed.
        if (needsForward && knownAnchors.contains(forwardAnchor)) {
            LOGGER.debug("[DungeonTrain] Appender skipping already-spawned forward anchor={} for trainId={} (in registry)",
                forwardAnchor, trainId);
            needsForward = false;
        }
        if (needsBackward && knownAnchors.contains(backwardAnchor)) {
            LOGGER.debug("[DungeonTrain] Appender skipping already-spawned backward anchor={} for trainId={} (in registry)",
                backwardAnchor, trainId);
            needsBackward = false;
        }
        if (!needsForward && !needsBackward) return false;

        // Diagnostic: every time we're about to spawn, log the train state
        // we based the decision on. Helps catch "appender thinks tail is X
        // but a sub-level at X-groupSize actually exists" — which causes
        // duplicate spawns on top of existing groups.
        if (LOGGER.isDebugEnabled()) {
            StringBuilder pidxs = new StringBuilder();
            for (Trains.Carriage c : train) {
                if (pidxs.length() > 0) pidxs.append(",");
                pidxs.append(c.provider().getPIdx());
            }
            LOGGER.debug("[DungeonTrain] Appender about to spawn forward={}({}) backward={}({}) (trainAnchor=[{},{}] trainPIdxList=[{}] players={})",
                needsForward, forwardAnchor, needsBackward, backwardAnchor,
                trainMinAnchor, trainMaxAnchor, pidxs, nearPlayerPIdxs);
        }

        // No more Sable-lag deferral here. Previously the appender waited
        // for Sable's visible list to match the spawn registry before
        // spawning, but Sable's plot view is player-relative and culls
        // sub-levels far from the player — so the visible list can stay
        // permanently behind the registry, deferring spawns indefinitely.
        // {@link #adjustForCollisions} now consults BOTH the visible
        // train AND {@link Trains#knownGroups}, so an in-flight or culled
        // sibling still participates in the collision check, and the
        // placement-math anchor delta absorbs any visible-list staleness.

        // Record the next-planned-spawn for the wireframe preview, one
        // entry per direction. Always refresh from the would-be anchor
        // for each direction (the spawn that would happen next if
        // MANUAL_SPAWN was triggered now), so the wireframe tracks the
        // reference carriage's drift even when we're not spawning. With
        // both lanes independent, the train can show two simultaneous
        // previews — one at each end. {@code trainsTouchedThisTick.add(
        // trainId)} already happened at the top of this method.
        if (needsForward) {
            Plan plan = planSpawnPlacement(lead, forwardAnchor, groupSize, dims, train);
            NEXT_PLANNED_SPAWNS_FORWARD.put(trainId, new PlannedSpawn(
                trainId,
                lead.ship().subLevelId(),
                plan.origin,
                plan.subLevelStride,
                plan.sizeY,
                plan.sizeZ,
                forwardAnchor));
        } else {
            NEXT_PLANNED_SPAWNS_FORWARD.remove(trainId);
        }
        if (needsBackward) {
            Plan plan = planSpawnPlacement(tail, backwardAnchor, groupSize, dims, train);
            NEXT_PLANNED_SPAWNS_BACKWARD.put(trainId, new PlannedSpawn(
                trainId,
                tail.ship().subLevelId(),
                plan.origin,
                plan.subLevelStride,
                plan.sizeY,
                plan.sizeZ,
                backwardAnchor));
        } else {
            NEXT_PLANNED_SPAWNS_BACKWARD.remove(trainId);
        }

        if (!spawnAllowedThisTick) return false;

        // Wait-for-placement-success gate, evaluated INDEPENDENTLY per
        // direction. Each direction's previous spawn must have transitioned
        // to {@code placedSuccessfully = true} via the per-tick
        // {@link #runPlacementCollisionTracker} — i.e. it has run
        // {@link #CLEAN_TICKS_FOR_SUCCESS} consecutive collision-free
        // game ticks AND any required ±0.5-X shifts have already separated
        // it from its predecessor — before its lane fires again. The
        // other direction's gate has no effect on this one: a forward
        // spawn settling at +X never holds up a backward spawn at −X.
        //
        // This subsumes the older AABB-non-zero and 20-tick-floor gates:
        // a successfully-placed carriage has by definition gone 60 ticks
        // without overlapping, so its AABB is settled and well past any
        // Sable plot/mass-tracker latency.
        long now = level.getGameTime();
        boolean spawnedAny = false;

        if (needsForward && isLanePlacementGateClear(LAST_SPAWNED_SHIP_FORWARD, trainId)) {
            ManagedShip newShip = spawnNewGroup(level, lead, forwardAnchor, groupSize, dims, velocity, trainId, train);
            if (newShip.getKinematicDriver() instanceof TrainTransformProvider newProvider) {
                newProvider.setSpawnedBackward(false);
            }
            LAST_SPAWNED_SHIP_FORWARD.put(trainId, newShip);
            LAST_SPAWNED_TICK_FORWARD.put(trainId, now);
            recordPostSpawnCollisionCheck(trainId, newShip, forwardAnchor, train);
            announceSpawn(level, forwardAnchor);
            spawnedAny = true;
        }

        if (needsBackward && isLanePlacementGateClear(LAST_SPAWNED_SHIP_BACKWARD, trainId)) {
            ManagedShip newShip = spawnNewGroup(level, tail, backwardAnchor, groupSize, dims, velocity, trainId, train);
            if (newShip.getKinematicDriver() instanceof TrainTransformProvider newProvider) {
                newProvider.setSpawnedBackward(true);
            }
            LAST_SPAWNED_SHIP_BACKWARD.put(trainId, newShip);
            LAST_SPAWNED_TICK_BACKWARD.put(trainId, now);
            recordPostSpawnCollisionCheck(trainId, newShip, backwardAnchor, train);
            announceSpawn(level, backwardAnchor);
            spawnedAny = true;
        }
        return spawnedAny;
    }

    /**
     * Per-direction placement-success gate. Returns {@code true} iff the
     * direction's previous spawn (if any) has transitioned to
     * {@code placedSuccessfully = true} on its
     * {@link TrainTransformProvider}. Removes the entry from {@code lane}
     * once cleared so the gate's "hot" set stays bounded to in-flight ships.
     */
    private static boolean isLanePlacementGateClear(Map<UUID, ManagedShip> lane, UUID trainId) {
        ManagedShip pending = lane.get(trainId);
        if (pending == null) return true;
        if (pending.getKinematicDriver() instanceof TrainTransformProvider provider
            && !provider.isPlacedSuccessfully()) {
            return false;
        }
        lane.remove(trainId);
        return true;
    }

    /**
     * Confirm a spawn in chat (action bar). In manual mode it's the
     * J-press confirmation (always shown — pressing J is a deliberate
     * user action). In auto mode the message fires only when the
     * "Train Spawn" chat-log toggle is on (X menu → Debug → Chat Logs)
     * so the chat isn't spammed during normal gameplay — independent
     * of the wireframe flags so a player can keep visual overlays on
     * without the chat noise.
     */
    private static void announceSpawn(ServerLevel level, int newAnchor) {
        if (!MANUAL_MODE && !games.brennan.dungeontrain.debug.DebugFlags.chatTrainSpawn()) return;
        for (ServerPlayer player : level.players()) {
            player.displayClientMessage(
                Component.literal(
                    "[DungeonTrain] Spawned group anchorPIdx=" + newAnchor
                ).withStyle(ChatFormatting.GREEN),
                true);
        }
    }

    /**
     * Pure decision helper — exposed package-private for unit tests.
     * Given the train's current min/max group anchors and the resolved
     * needed pIdx range (already unioned across all near players, with
     * each player's halfBack/halfFront applied by the caller), return the
     * list of new group anchors to spawn this tick in spawn order: forward
     * anchors ascending, then backward anchors descending. Anchors already
     * inside {@code [trainMinAnchor, trainMaxAnchor]} are never re-emitted.
     *
     * @param trainMaxAnchor current lead anchor pIdx
     * @param trainMinAnchor current tail anchor pIdx
     * @param maxNeededPIdx  highest pIdx any player needs (player.pIdx + halfFront)
     * @param minNeededPIdx  lowest pIdx any player needs (player.pIdx − halfBack)
     * @param groupSize      carriages per group (≥ 1)
     */
    static List<Integer> computeGroupAnchorsToSpawn(
        int trainMaxAnchor,
        int trainMinAnchor,
        int maxNeededPIdx,
        int minNeededPIdx,
        int groupSize
    ) {
        if (groupSize < 1) {
            throw new IllegalArgumentException("groupSize must be ≥ 1, got " + groupSize);
        }
        if (maxNeededPIdx == Integer.MIN_VALUE || minNeededPIdx == Integer.MAX_VALUE) {
            return List.of();
        }
        // Snap needed pIdx range outward to group anchors. Math.floorDiv
        // handles negative pIdx correctly.
        int maxNeededAnchor = Math.floorDiv(maxNeededPIdx, groupSize) * groupSize;
        int minNeededAnchor = Math.floorDiv(minNeededPIdx, groupSize) * groupSize;

        List<Integer> out = new ArrayList<>();
        for (int a = trainMaxAnchor + groupSize; a <= maxNeededAnchor; a += groupSize) {
            out.add(a);
        }
        for (int a = trainMinAnchor - groupSize; a >= minNeededAnchor; a -= groupSize) {
            out.add(a);
        }
        return out;
    }

    /**
     * Compute the per-player target carriage count from the player's
     * render distance. Used when the {@code numCarriages} config is set
     * to {@code 0} (auto). Falls back to the server-wide view distance
     * if the player hasn't reported their setting yet (early-join window),
     * then to a hardcoded 10-chunk floor if even that is unavailable
     * (dedicated server with no setting). Clamps to
     * {@link DungeonTrainConfig#MIN_CARRIAGES_AUTO_FLOOR} ..
     * {@link DungeonTrainConfig#MAX_CARRIAGES} so very low or very high
     * rd values still produce a sensible train length.
     */
    static int autoTargetFromRenderDistance(ServerPlayer player, int carriageLength) {
        int rdChunks = player.requestedViewDistance();
        if (rdChunks <= 0) {
            rdChunks = player.serverLevel().getServer().getPlayerList().getViewDistance();
            if (rdChunks <= 0) rdChunks = 10;
        }
        int rdBlocks = rdChunks * 16;
        int target = (rdBlocks * 2) / Math.max(1, carriageLength);
        return Math.max(DungeonTrainConfig.MIN_CARRIAGES_AUTO_FLOOR,
                        Math.min(DungeonTrainConfig.MAX_CARRIAGES, target));
    }

    /**
     * Place a new {@code groupSize}-carriage sub-level at the world position
     * extrapolated from {@code reference}'s current world origin, with a
     * deliberate gap that guarantees no Sable rigid-body collision.
     *
     * <p><b>Why a gap.</b> Sable's collision response pushes intersecting
     * (or even very-near-touching) bodies apart, manifesting as visible
     * "jumping" of the train. We bias the rounding by
     * {@link #MIN_GAP_BLOCKS} so the visible gap between the new group's
     * lowest-X face and the reference's nearest face is always strictly
     * positive (range {@code [MIN_GAP_BLOCKS, 1 + MIN_GAP_BLOCKS]} blocks).</p>
     */
    private static ManagedShip spawnNewGroup(
        ServerLevel level,
        Trains.Carriage reference,
        int newAnchor,
        int groupSize,
        CarriageDims dims,
        Vector3dc velocity,
        UUID trainId,
        List<Trains.Carriage> train
    ) {
        Plan plan = planSpawnPlacement(reference, newAnchor, groupSize, dims, train);
        ManagedShip newShip = TrainAssembler.spawnGroup(
            level, plan.origin, velocity, newAnchor, groupSize, dims, trainId);

        LOGGER.info("[DungeonTrain] Appender added group anchorPIdx={} groupSize={} trainId={} ship id={} placedAt={} (idealX={}, dir={}, gapBlocks={}, subLevelStride={}, collisionAdjustments={})",
            newAnchor, groupSize, trainId, newShip.id(), plan.origin,
            String.format("%.4f", plan.idealX),
            plan.forward ? "forward" : "backward",
            String.format("%.4f", plan.gap),
            plan.subLevelStride,
            plan.collisionAdjustments);

        markCollidingNeighbours(level, newShip, newAnchor, train);
        return newShip;
    }

    /**
     * Pure-ish placement helper: replays {@link #spawnNewGroup}'s ideal-X
     * derivation, the {@link #MIN_GAP_BLOCKS}-rounding bias, and the
     * iterative {@link #adjustForCollisions} pass — but stops before
     * {@link TrainAssembler#spawnGroup}, so callers can inspect the planned
     * placement (debug-overlay preview, {@code /dt manualspawn next}) without
     * actually creating a sub-level.
     *
     * <p>The "pure-ish" caveat is that {@link #adjustForCollisions} reads
     * each sibling's live {@code worldAABB()} every iteration, so the
     * returned placement reflects the train's CURRENT layout — call it from
     * the same tick you intend to spawn for consistent results.</p>
     */
    private static Plan planSpawnPlacement(
        Trains.Carriage reference,
        int newAnchor,
        int groupSize,
        CarriageDims dims,
        List<Trains.Carriage> train
    ) {
        BlockPos refShipyardOrigin = reference.provider().getShipyardOrigin();
        int refAnchor = reference.provider().getPIdx();
        UUID refTrainId = reference.provider().getTrainId();
        int length = dims.length();
        int halfPadLen = CarriagePlacer.halfPadLen(dims);

        int subLevelStride = (groupSize > 1) ? (groupSize * length + 2 * halfPadLen) : length;

        Vector3d refWorldOriginVec = new Vector3d(
            refShipyardOrigin.getX(), refShipyardOrigin.getY(), refShipyardOrigin.getZ());
        reference.ship().shipToWorld(refWorldOriginVec);

        int anchorDelta = newAnchor - refAnchor;
        int subLevelDelta = anchorDelta / groupSize;
        double idealX = refWorldOriginVec.x + subLevelDelta * (double) subLevelStride;
        double idealY = refWorldOriginVec.y;
        double idealZ = refWorldOriginVec.z;

        boolean forward = newAnchor > refAnchor;
        int initialPlaceX = forward
            ? (int) Math.ceil(idealX + MIN_GAP_BLOCKS)
            : (int) Math.floor(idealX - MIN_GAP_BLOCKS);
        int placeY = (int) Math.round(idealY);
        int placeZ = (int) Math.round(idealZ);

        int adjustedPlaceX = adjustForCollisions(
            initialPlaceX, placeY, placeZ, subLevelStride, dims, train, refTrainId, forward, newAnchor);

        BlockPos origin = new BlockPos(adjustedPlaceX, placeY, placeZ);
        double gap = forward ? (adjustedPlaceX - idealX) : (idealX - adjustedPlaceX);

        return new Plan(
            origin,
            subLevelStride,
            dims.height(),
            dims.width(),
            idealX,
            gap,
            forward,
            adjustedPlaceX - initialPlaceX);
    }

    private record Plan(
        BlockPos origin,
        int subLevelStride,
        int sizeY,
        int sizeZ,
        double idealX,
        double gap,
        boolean forward,
        int collisionAdjustments
    ) {}

    /**
     * Iteratively shift {@code placeX} along the spawn direction until the
     * would-be sub-level AABB no longer overlaps any sibling's AABB.
     *
     * <p>Sibling set: the union of (a) the visible train (Sable's
     * {@link Shipyards#findAll}-derived list) and (b) the spawn-time
     * registry ({@link Trains#knownGroups}), deduped by ship id, skipping
     * zero-AABB ships. Auto-spawn pacing is handled separately by the
     * wait-for-Sable-settle check in {@link #updateTrain} so that by the
     * time we get here, the previous spawn's {@code worldAABB} is
     * non-zero and the collision pass can see it.</p>
     */
    private static int adjustForCollisions(
        int placeX,
        int placeY,
        int placeZ,
        int subLevelStride,
        CarriageDims dims,
        List<Trains.Carriage> train,
        UUID trainId,
        boolean forward,
        int newAnchor
    ) {
        int height = dims.height();
        int width = dims.width();
        List<long[]> siblingsForLog = new ArrayList<>();
        List<AABBdc> siblings = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Trains.Carriage other : train) {
            long id = other.ship().id();
            if (!seen.add(id)) continue;
            AABBdc aabb = other.ship().worldAABB();
            if (isZeroAabb(aabb)) continue;
            siblings.add(aabb);
            siblingsForLog.add(new long[] { id, other.provider().getPIdx() });
        }
        Map<Integer, ManagedShip> registry = Trains.knownGroups(trainId);
        for (Map.Entry<Integer, ManagedShip> e : registry.entrySet()) {
            ManagedShip ship = e.getValue();
            long id = ship.id();
            if (!seen.add(id)) continue;
            AABBdc aabb = ship.worldAABB();
            if (isZeroAabb(aabb)) continue;
            siblings.add(aabb);
            siblingsForLog.add(new long[] { id, e.getKey() });
        }

        for (int iter = 0; iter < COLLISION_ADJUST_SAFETY_LIMIT; iter++) {
            double candMinX = placeX;
            double candMaxX = placeX + subLevelStride;
            double candMinY = placeY;
            double candMaxY = placeY + height;
            double candMinZ = placeZ;
            double candMaxZ = placeZ + width;

            AABBdc colliding = null;
            int collidingPIdx = 0;
            for (int i = 0; i < siblings.size(); i++) {
                AABBdc o = siblings.get(i);
                if (candMaxX > o.minX() && candMinX < o.maxX()
                    && candMaxY > o.minY() && candMinY < o.maxY()
                    && candMaxZ > o.minZ() && candMinZ < o.maxZ()) {
                    colliding = o;
                    collidingPIdx = (int) siblingsForLog.get(i)[1];
                    break;
                }
            }
            if (colliding == null) {
                if (iter > 0) {
                    LOGGER.info("[DungeonTrain] Pre-spawn collision adjust resolved for newAnchor={} after {} iter(s); finalPlaceX={}",
                        newAnchor, iter, placeX);
                }
                return placeX;
            }

            int newPlaceX;
            if (forward) {
                newPlaceX = (int) Math.ceil(colliding.maxX() + MIN_GAP_BLOCKS);
            } else {
                newPlaceX = (int) Math.floor(colliding.minX() - MIN_GAP_BLOCKS) - subLevelStride;
            }
            if (newPlaceX == placeX) {
                LOGGER.warn("[DungeonTrain] Pre-spawn collision adjust stalled for newAnchor={} (forward={}) at placeX={}; offender pIdx={} aabbX=[{}, {}]; proceeding with stale placement",
                    newAnchor, forward, placeX, collidingPIdx,
                    String.format("%.3f", colliding.minX()),
                    String.format("%.3f", colliding.maxX()));
                return placeX;
            }
            LOGGER.debug("[DungeonTrain] Pre-spawn collision adjust iter={} newAnchor={}: shifted placeX {} → {} (offender pIdx={} offenderEdgeX={})",
                iter, newAnchor, placeX, newPlaceX, collidingPIdx,
                String.format("%.3f", forward ? colliding.maxX() : colliding.minX()));
            placeX = newPlaceX;
        }
        LOGGER.warn("[DungeonTrain] Pre-spawn collision adjust hit safety cap ({}) for newAnchor={} (forward={}); proceeding with placeX={}",
            COLLISION_ADJUST_SAFETY_LIMIT, newAnchor, forward, placeX);
        return placeX;
    }

    private static boolean isZeroAabb(AABBdc aabb) {
        return aabb.minX() == 0 && aabb.maxX() == 0
            && aabb.minY() == 0 && aabb.maxY() == 0
            && aabb.minZ() == 0 && aabb.maxZ() == 0;
    }

    /**
     * Bandaid identification: after a new group is spawned, compare its
     * world-space AABB against every other carriage already in this
     * train. On overlap, log a warning and place a redstone block on
     * the roof of the offending sub-level (in shipyard space, so the
     * marker moves with the train).
     *
     * <p>Strict AABB overlap only — the intended {@link #MIN_GAP_BLOCKS}
     * gap leaves AABBs strictly separated, so no false positives.</p>
     */
    private static void markCollidingNeighbours(
        ServerLevel level,
        ManagedShip newShip,
        int newAnchor,
        List<Trains.Carriage> train
    ) {
        AABBdc newAabb = newShip.worldAABB();
        for (Trains.Carriage other : train) {
            ManagedShip otherShip = other.ship();
            if (otherShip.id() == newShip.id()) continue;
            AABBdc otherAabb = otherShip.worldAABB();
            if (!aabbsOverlap(newAabb, otherAabb)) continue;

            BlockPos marker = roofMarkerPosOnShip(otherShip, otherAabb);
            int otherPIdx = other.provider().getPIdx();
            LOGGER.warn("[DungeonTrain] Carriage collision detected: newShip id={} pIdx={} overlapping otherShip id={} pIdx={}; marking with redstone block at shipyard pos {}",
                newShip.id(), newAnchor, otherShip.id(), otherPIdx, marker);
            SilentBlockOps.setBlockSilent(level, marker, Blocks.REDSTONE_BLOCK.defaultBlockState());
            // Chat broadcast is gated on the "Collision" chat-log toggle
            // (X menu → Debug → Chat Logs). The LOGGER.warn above and the
            // redstone marker stay unconditional — they're diagnostic
            // state, not chat noise.
            if (games.brennan.dungeontrain.debug.DebugFlags.chatCollision()) {
                level.getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal(
                        "[DungeonTrain] Carriage collision: new pIdx=" + newAnchor
                            + " overlapping pIdx=" + otherPIdx
                            + " — redstone marker placed on roof"
                    ).withStyle(ChatFormatting.RED),
                    false);
            }
        }
    }

    private static boolean aabbsOverlap(AABBdc a, AABBdc b) {
        return a.maxX() > b.minX() && a.minX() < b.maxX()
            && a.maxY() > b.minY() && a.minY() < b.maxY()
            && a.maxZ() > b.minZ() && a.minZ() < b.maxZ();
    }

    /**
     * Run the diagnostic 1×3×5 post-spawn collision check at the new
     * carriage's first block (lowest-X corner of its sub-level footprint).
     * AABB-vs-AABB against every other carriage of the same train (visible
     * + registry, deduped by ship id, skipping zero/degenerate AABBs); the
     * result lands in {@link #LAST_SPAWN_COLLISION_CHECK} for the wireframe
     * overlay drawn by
     * {@link games.brennan.dungeontrain.client.CarriageGroupGapDebugRenderer}.
     *
     * <p>The new ship's {@code worldAABB} is typically still zero on the
     * spawn tick (Sable hasn't ticked it yet), so we derive the check
     * region from the sub-level's pose-translated shipyard origin instead
     * of from the AABB. {@link #planSpawnPlacement} placed the back pad's
     * lowest-X corner at the spawn-tick {@code shipToWorld(shipyardOrigin)},
     * which is what we re-derive here so the box lands exactly where the
     * carriage starts.</p>
     */
    private static void recordPostSpawnCollisionCheck(
        UUID trainId,
        ManagedShip newShip,
        int newAnchor,
        List<Trains.Carriage> train
    ) {
        // The 1×3×5 box anchors at the new sub-level's first block in
        // SHIPYARD coordinates — fixed for the sub-level's lifetime, so the
        // wireframe rides the carriage perfectly via {@code shipToWorld}
        // every frame on the client.
        if (!(newShip.getKinematicDriver() instanceof TrainTransformProvider provider)) {
            return; // shouldn't happen — the spawn path always sets the driver
        }
        BlockPos shipyardOrigin = provider.getShipyardOrigin();

        // For the AABB-vs-AABB check we need world-space bounds of the box,
        // computed from the new ship's CURRENT pose so the comparison
        // matches where the just-placed blocks actually live in the world.
        Vector3d cornerVec = new Vector3d(
            shipyardOrigin.getX(), shipyardOrigin.getY(), shipyardOrigin.getZ());
        newShip.shipToWorld(cornerVec);
        double checkMinX = cornerVec.x;
        double checkMinY = cornerVec.y;
        double checkMinZ = cornerVec.z;
        double checkMaxX = checkMinX + COLLISION_CHECK_SIZE_X;
        double checkMaxY = checkMinY + COLLISION_CHECK_SIZE_Y;
        double checkMaxZ = checkMinZ + COLLISION_CHECK_SIZE_Z;

        boolean colliding = false;
        int collidingPIdx = 0;
        long newId = newShip.id();

        // Walk visible train ∪ registry, deduped by id, skipping the new
        // ship itself (its AABB is still zero anyway) and any
        // zero/degenerate AABBs (unsafe to compare against).
        Set<Long> seen = new HashSet<>();
        seen.add(newId);
        for (Trains.Carriage other : train) {
            if (!seen.add(other.ship().id())) continue;
            AABBdc aabb = other.ship().worldAABB();
            if (isZeroAabb(aabb)) continue;
            if (checkMaxX > aabb.minX() && checkMinX < aabb.maxX()
                && checkMaxY > aabb.minY() && checkMinY < aabb.maxY()
                && checkMaxZ > aabb.minZ() && checkMinZ < aabb.maxZ()) {
                colliding = true;
                collidingPIdx = other.provider().getPIdx();
                break;
            }
        }
        if (!colliding) {
            Map<Integer, ManagedShip> registry = Trains.knownGroups(trainId);
            for (Map.Entry<Integer, ManagedShip> e : registry.entrySet()) {
                ManagedShip ship = e.getValue();
                if (!seen.add(ship.id())) continue;
                AABBdc aabb = ship.worldAABB();
                if (isZeroAabb(aabb)) continue;
                if (checkMaxX > aabb.minX() && checkMinX < aabb.maxX()
                    && checkMaxY > aabb.minY() && checkMinY < aabb.maxY()
                    && checkMaxZ > aabb.minZ() && checkMinZ < aabb.maxZ()) {
                    colliding = true;
                    collidingPIdx = e.getKey();
                    break;
                }
            }
        }

        if (colliding) {
            LOGGER.warn("[DungeonTrain] Post-spawn collision check: newAnchor={} shipyardOrigin={} 1x3y5z box overlaps pIdx={}",
                newAnchor, shipyardOrigin, collidingPIdx);
        } else {
            LOGGER.debug("[DungeonTrain] Post-spawn collision check: newAnchor={} shipyardOrigin={} 1x3y5z box clear",
                newAnchor, shipyardOrigin);
        }

        LAST_SPAWN_COLLISION_CHECK.put(trainId, new SpawnCollisionCheck(
            trainId,
            newShip.subLevelId(),
            newAnchor,
            0L,
            shipyardOrigin,
            COLLISION_CHECK_SIZE_X,
            COLLISION_CHECK_SIZE_Y,
            COLLISION_CHECK_SIZE_Z,
            colliding,
            collidingPIdx));
    }

    /**
     * Roof-marker position in {@code ship}'s shipyard space — one block
     * above the AABB's top, centred horizontally. Converts the
     * world-space target through {@link ManagedShip#worldToShip} so the
     * resulting {@link BlockPos} lands on the sub-level (not the static
     * world) and travels with the train.
     */
    private static BlockPos roofMarkerPosOnShip(ManagedShip ship, AABBdc worldAabb) {
        Vector3d worldTopCenter = new Vector3d(
            (worldAabb.minX() + worldAabb.maxX()) / 2.0,
            worldAabb.maxY() + 1.0,
            (worldAabb.minZ() + worldAabb.maxZ()) / 2.0);
        ship.worldToShip(worldTopCenter);
        return new BlockPos(
            (int) Math.round(worldTopCenter.x),
            (int) Math.round(worldTopCenter.y),
            (int) Math.round(worldTopCenter.z));
    }

    /**
     * Clear the HUD for any player who had a pIdx last tick but wasn't
     * reached by any train this tick — they walked outside {@link #NEAR_RADIUS}.
     */
    private static void clearDropouts(ServerLevel level, Set<UUID> seenThisTick) {
        if (LAST_SENT_PIDX.isEmpty()) return;
        Iterator<Map.Entry<UUID, Integer>> it = LAST_SENT_PIDX.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            UUID uuid = entry.getKey();
            if (seenThisTick.contains(uuid)) continue;
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(uuid);
            if (player != null) {
                DungeonTrainNet.sendTo(player, CarriageIndexPacket.absent());
            }
            it.remove();
        }
    }
}
