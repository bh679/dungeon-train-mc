package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.CarriageIndexPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
     * Per-train, per-direction: the tick at which we first wanted to spawn
     * but couldn't (gate closed or anchor duplicated). Cleared whenever a
     * spawn fires in that direction or the direction stops being needed.
     * Used by {@link #detectAndAnnounceStall} to flag stalls in chat.
     */
    private static final Map<UUID, Long> BLOCKED_SINCE_FORWARD = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> BLOCKED_SINCE_BACKWARD = new ConcurrentHashMap<>();

    /**
     * Per-train, per-direction: one-shot latch — true once we've already
     * chatted about the current stall in this direction. Prevents repeating
     * the warning every tick. Reset alongside {@link #BLOCKED_SINCE_FORWARD}
     * / {@link #BLOCKED_SINCE_BACKWARD} when a spawn fires or need clears.
     */
    private static final Map<UUID, Boolean> STALL_WARNED_FORWARD = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> STALL_WARNED_BACKWARD = new ConcurrentHashMap<>();

    /**
     * Per-train, per-direction: latch — true once
     * {@link #isLanePlacementGateClear} has fired a cull-clear in this
     * direction for this train. Prevents the cull-clear path from cascading
     * unboundedly: the lane gets at most ONE cull-clear per natural
     * placement success. The latch is cleared the next time a spawn in this
     * direction reaches {@code placedSuccessfully} via the normal tracker
     * path — at that point the train has caught up to the player, Sable's
     * plot covers the train's end, and we're safe to allow another
     * cull-clear if a future spawn is culled. While the latch is set, the
     * gate stays closed even after the pending sub-level is removed —
     * extension in that direction halts until placement actually succeeds.
     */
    private static final Map<UUID, Boolean> CULL_CLEARED_FORWARD = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> CULL_CLEARED_BACKWARD = new ConcurrentHashMap<>();

    /**
     * Stall threshold: 600 ticks = 30 s at 20 Hz. Comfortably past the
     * 60-tick {@link #CLEAN_TICKS_FOR_SUCCESS} settle window so a normally
     * operating train (which blocks for ~60-100 ticks between spawns)
     * never trips this. Tunable if false positives or missed stalls appear.
     */
    private static final int STUCK_THRESHOLD_TICKS = 600;

    /**
     * Master kill-switch for {@link #detectAndAnnounceStall}. Off by
     * default — the placement-tracker safety valves (PR #212) make the
     * carriage-spawn stall a rare event, so the diagnostic is opt-in.
     * Flip to {@code true} (and rebuild) when investigating a regression
     * where carriages stop being appended despite a near player. When
     * off, the appender's spawn loop runs bit-identical to the
     * pre-diagnostic build (no map ops, no {@code LOGGER.warn}, no chat
     * broadcast).
     */
    private static volatile boolean STALL_DETECTION_ENABLED = false;


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
     * Grace period after a spawn before {@link #isLanePlacementGateClear}
     * is allowed to declare the pending ship "culled by Sable". Sable's
     * {@code findAll()} doesn't include a freshly-spawned sub-level
     * immediately — observed registration lag is 2-5 ticks. Without a
     * grace window, the cull check fires on tick N+1 (false positive),
     * the lane re-opens, the next spawn fires, that ship is also "culled"
     * a tick later, and the appender produces a runaway sequence of
     * gap-creating spawns. 60 ticks matches {@link #CLEAN_TICKS_FOR_SUCCESS}
     * so a normally-settling ship reaches {@code placedSuccessfully}
     * before the cull check ever fires; the cull-clear path then only
     * activates for ships that genuinely never appeared in Sable's plot.
     */
    private static final long CULL_DETECTION_GRACE_TICKS = 60L;
    /**
     * Maximum distance (in blocks, world space) from any player to a
     * placement-settled carriage's current world position at which the
     * carriage's deferred contents-entity spawn will fire. Carriages further
     * than this hold their pending spawns indefinitely until a player
     * approaches (or until the rolling-window cleanup drops the carriage,
     * at which point the pending array is GC'd along with the provider).
     *
     * <p>Rationale: every carriage spawned far ahead used to fire its mobs
     * at placement-success time. Mobs then wandered between adjacent
     * carriages of the same group (whose internal walls are passable via
     * doors/windows) for tens of seconds before the player walked in,
     * making them visibly land in "the wrong carriage." Deferring spawn
     * until the player is close cuts the wander window to ~0.</p>
     *
     * <p>48 ≈ 3 chunks — comfortably inside any sensible render distance
     * so spawn-pop-in isn't visible; comfortably outside vanilla's entity
     * activation radius (32) so mobs aren't already in stasis on first
     * sight.</p>
     */
    private static final double SPAWN_RADIUS_BLOCKS = 48.0;
    private static final double SPAWN_RADIUS_SQ = SPAWN_RADIUS_BLOCKS * SPAWN_RADIUS_BLOCKS;

    /**
     * Hard ceiling on how many game ticks the placement-collision tracker
     * will keep a carriage in the unplaced state. If
     * {@code ticksSinceFirstSeen > MAX_PLACEMENT_SETTLE_TICKS} and the
     * carriage has still not flipped {@link TrainTransformProvider#markPlacedSuccessfully},
     * the tracker force-finalises it with a WARN log capturing the full
     * state at the time of release.
     *
     * <p>Exists because two pathological stalls were observed in 0.167.0
     * testing: (a) a colliding carriage that kept shifting +0.5 X every
     * tick but the world AABB used by {@link #checkOneCarriage} lagged
     * behind the cumulative shift — so the collision never cleared; (b) a
     * "silent" carriage whose Sable physics tick never fired, leaving the
     * placement tracker in a no-op state indefinitely. Both stalls
     * permanently blocked the next spawn via the wait-for-placedSuccessfully
     * lane gate.</p>
     *
     * <p>200 ticks = 10 seconds — 3× the legitimate 60-clean-tick window,
     * but short enough that a player walking the train length doesn't
     * notice the stall. The blocks are already placed at shipyard coords
     * regardless of {@code spawnWorldPos}; the worst visible artefact of
     * a premature force-finalise is a small overlap with the colliding
     * sibling. Strictly better than a totally-blocked train.</p>
     */
    private static final int MAX_PLACEMENT_SETTLE_TICKS = 200;

    /**
     * Approach window before {@link #MAX_PLACEMENT_SETTLE_TICKS} during
     * which the placement tracker emits a per-second state-snapshot log
     * line, so the divergence leading up to the safety-valve fire is
     * captured in the log even if the carriage settles legitimately at
     * the last moment.
     */
    private static final int PLACEMENT_STALL_APPROACH_TICKS = 40;

    /**
     * Tick at which each placement-tracked sub-level was first seen by
     * {@link #runPlacementCollisionTracker}. Keyed by Sable sub-level id
     * (matches {@code carriage.ship().subLevelId()}). Used by the
     * safety-valve to bound the unplaced lifetime — handles the case
     * where {@link TrainTransformProvider#getSpawnGameTick} is still -1
     * (Sable hasn't fired the ship's first physics tick yet).
     *
     * <p>Entries are removed when the carriage is force-finalised by the
     * safety valve, when it naturally reaches {@code placedSuccessfully},
     * or by a per-tick reconciliation pass against the current
     * {@link Trains#byTrainId} membership (handles rolling-window despawn).
     * </p>
     */
    private static final Map<UUID, Long> PLACEMENT_TRACKER_FIRST_SEEN = new ConcurrentHashMap<>();

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

    /**
     * If a not-yet-placed carriage's X-axis gap to its train-facing neighbour
     * exceeds this many blocks, the per-tick placement tracker shifts the
     * carriage TOWARD that neighbour by {@link #COLLISION_SHIFT_BLOCKS} this
     * tick and resets the clean-tick counter. Symmetric dual of the
     * collision-pushback branch: collisions push apart, big gaps pull together.
     * Both branches feed the same dead-band [MIN_GAP_BLOCKS, MAX_GAP_BLOCKS]
     * that the carriage must rest inside for {@link #CLEAN_TICKS_FOR_SUCCESS}
     * consecutive ticks before {@code placedSuccessfully} fires.
     *
     * <p>Closes a 3-block gap in ~6 ticks at 0.5/tick — finishes well inside
     * the 60-tick clean window so spawn-time placement overshoot never
     * permanently strands a carriage at the wrong distance.</p>
     */
    private static final double MAX_GAP_BLOCKS = 3.0;

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
        Set<UUID> liveSubLevelIds = new HashSet<>();
        for (Map.Entry<UUID, List<Trains.Carriage>> entry : trains.entrySet()) {
            UUID trainId = entry.getKey();
            List<Trains.Carriage> train = entry.getValue();
            for (Trains.Carriage carriage : train) {
                TrainTransformProvider provider = carriage.provider();
                UUID subLevelId = carriage.ship().subLevelId();
                liveSubLevelIds.add(subLevelId);
                if (provider.isPlacedSuccessfully()) {
                    PLACEMENT_TRACKER_FIRST_SEEN.remove(subLevelId);
                    continue;
                }

                // Safety valve — bound the unplaced lifetime. Two pre-0.167.1
                // stalls bypassed the natural 60-clean-tick path: a
                // collide-loop where the world AABB lagged behind cumulative
                // shifts, and a silent stall where Sable never fired the
                // ship's first physics tick. Both blocked the next lane spawn
                // permanently. Force-finalise after MAX_PLACEMENT_SETTLE_TICKS
                // with a WARN snapshot so the underlying bug stays visible.
                long firstSeenTick = PLACEMENT_TRACKER_FIRST_SEEN.computeIfAbsent(subLevelId, k -> now);
                long ticksSinceFirstSeen = now - firstSeenTick;
                if (ticksSinceFirstSeen > MAX_PLACEMENT_SETTLE_TICKS) {
                    logPlacementStallState(trainId, carriage, train, provider, now, ticksSinceFirstSeen, "SAFETY-VALVE-FIRE");
                    provider.markPlacedSuccessfully();
                    PLACEMENT_TRACKER_FIRST_SEEN.remove(subLevelId);
                    continue;
                }
                if (ticksSinceFirstSeen > MAX_PLACEMENT_SETTLE_TICKS - PLACEMENT_STALL_APPROACH_TICKS
                    && (ticksSinceFirstSeen % 20) == 0) {
                    logPlacementStallState(trainId, carriage, train, provider, now, ticksSinceFirstSeen, "APPROACHING-VALVE");
                }

                SpawnCollisionCheck check = checkOneCarriage(trainId, carriage, train, now);
                if (check == null) continue;

                // Drive the collide→move-together→collide lock BEFORE the
                // shift decision so this tick's pushback observation can
                // suppress this tick's move-together (the carriage doesn't
                // get a "one last move-together" after the locking collision).
                boolean lockFiredThisTick = false;
                if (check.colliding()) {
                    if (provider.hasRunMoveTogetherAfterCollision() && !provider.isMoveTogetherLocked()) {
                        provider.markMoveTogetherLocked();
                        lockFiredThisTick = true;
                    }
                    provider.markCollidedDuringPlacement();
                }

                // Three branches:
                //   colliding             → shift AWAY from offender (existing)
                //   gap > MAX_GAP_BLOCKS  → shift TOWARD train-facing sibling (new)
                //   otherwise             → clean tick, eventually placedSuccessfully
                double gap = check.colliding()
                    ? 0.0
                    : gapToTrainFacingSibling(trainId, carriage, train);
                double dx = placementTrackerShiftDx(
                    check.colliding(),
                    check.selfPIdx(),
                    check.collidingPIdx(),
                    gap,
                    provider.isSpawnedBackward(),
                    provider.isMoveTogetherLocked());

                if (dx != 0.0) {
                    provider.shiftSpawnPosition(dx, 0.0, 0.0);
                    provider.resetConsecutiveCleanTicks();
                    if (check.colliding()) {
                        LOGGER.info("[DungeonTrain] Placement tracker: pIdx={} colliding (overlaps pIdx={}) — shifted {} X, timer reset",
                            provider.getPIdx(), check.collidingPIdx(),
                            String.format("%+.1f", dx));
                        if (lockFiredThisTick) {
                            LOGGER.info("[DungeonTrain] Placement tracker: pIdx={} move-together LOCKED (collide→move-together→collide cycle observed)",
                                provider.getPIdx());
                        }
                    } else {
                        // Move-together fired. Mark "after-collision" if a
                        // collision has already been observed during placement —
                        // this is the middle leg of the collide→move-together→collide
                        // cycle. The next collision will then lock.
                        if (provider.hasCollidedDuringPlacement() && !provider.hasRunMoveTogetherAfterCollision()) {
                            provider.markRunMoveTogetherAfterCollision();
                        }
                        LOGGER.info("[DungeonTrain] Placement tracker: pIdx={} too-far (gap={} blocks > {}) — shifted {} X, timer reset",
                            provider.getPIdx(),
                            String.format("%.2f", gap),
                            MAX_GAP_BLOCKS,
                            String.format("%+.1f", dx));
                    }
                } else {
                    provider.incrementConsecutiveCleanTicks();
                    if (provider.getConsecutiveCleanTicks() >= CLEAN_TICKS_FOR_SUCCESS) {
                        provider.markPlacedSuccessfully();
                        PLACEMENT_TRACKER_FIRST_SEEN.remove(subLevelId);
                        LOGGER.info("[DungeonTrain] Placement tracker: pIdx={} placed successfully after {} clean ticks (ticksSinceSpawn={})",
                            provider.getPIdx(), CLEAN_TICKS_FOR_SUCCESS, check.ticksSinceSpawn());
                        // Entity spawn is no longer fired here — it's gated on
                        // player proximity by {@link #tickPendingEntitySpawnDistanceGate}
                        // so mobs don't get a head-start to wander between
                        // adjacent carriages before the player arrives.
                    }
                }
            }
        }
        // Drop tracking entries for sub-levels no longer in any train.
        // Handles rolling-window cleanup so the map can't accumulate
        // entries for despawned groups.
        PLACEMENT_TRACKER_FIRST_SEEN.keySet().retainAll(liveSubLevelIds);
    }

    /**
     * Format and emit a placement-stall state snapshot. Called both by
     * the safety valve at force-finalisation and by the approach-window
     * per-second instrumentation. {@code reason} tags the log line so
     * a single grep separates the two cases.
     *
     * <p>Captures everything needed to debug the underlying bug — the
     * provider's spawn-tick / canonical-pos / shift state, the world AABB
     * the collision check is actually using, and the colliding sibling's
     * AABB if any. Survives the case where the ship has never been
     * physics-ticked ({@code spawnGameTick == -1}, {@code canonicalPos == null}).</p>
     */
    private static void logPlacementStallState(
        UUID trainId,
        Trains.Carriage carriage,
        List<Trains.Carriage> train,
        TrainTransformProvider provider,
        long now,
        long ticksSinceFirstSeen,
        String reason
    ) {
        long spawnGameTick = provider.getSpawnGameTick();
        long ticksSinceSpawn = (spawnGameTick < 0L) ? -1L : (now - spawnGameTick);
        Vector3dc canonicalPos = provider.getCanonicalPos();
        BlockPos shipyardOrigin = provider.getShipyardOrigin();
        String canonicalPosStr = (canonicalPos == null)
            ? "null(no-physics-tick)"
            : String.format("(%.3f,%.3f,%.3f)", canonicalPos.x(), canonicalPos.y(), canonicalPos.z());
        Vector3d cornerProbe = new Vector3d(
            shipyardOrigin.getX(), shipyardOrigin.getY(), shipyardOrigin.getZ());
        try {
            carriage.ship().shipToWorld(cornerProbe);
        } catch (Throwable t) {
            cornerProbe.set(Double.NaN, Double.NaN, Double.NaN);
        }
        AABBdc selfAabb = null;
        try {
            selfAabb = carriage.ship().worldAABB();
        } catch (Throwable ignored) {}
        String selfAabbStr = (selfAabb == null || isZeroAabb(selfAabb))
            ? "zero/null"
            : String.format("[%.2f..%.2f, %.2f..%.2f, %.2f..%.2f]",
                selfAabb.minX(), selfAabb.maxX(),
                selfAabb.minY(), selfAabb.maxY(),
                selfAabb.minZ(), selfAabb.maxZ());
        SpawnCollisionCheck check = checkOneCarriage(trainId, carriage, train, now);
        String collidingStr = (check == null)
            ? "check=null"
            : String.format("colliding=%s collidingPIdx=%d",
                check.colliding(), check.collidingPIdx());
        LOGGER.warn(
            "[DungeonTrain] Placement-stall [{}] pIdx={} subLevelId={} ticksSinceFirstSeen={} ticksSinceSpawn={} consecutiveCleanTicks={} canonicalPos={} shipyardOrigin=({},{},{}) shipToWorldCorner=({},{},{}) selfAABB={} {} flags=[collided={}, ranMoveTogetherAfterCollision={}, moveTogetherLocked={}, spawnedBackward={}]",
            reason,
            provider.getPIdx(),
            carriage.ship().subLevelId(),
            ticksSinceFirstSeen,
            ticksSinceSpawn,
            provider.getConsecutiveCleanTicks(),
            canonicalPosStr,
            shipyardOrigin.getX(), shipyardOrigin.getY(), shipyardOrigin.getZ(),
            String.format("%.3f", cornerProbe.x),
            String.format("%.3f", cornerProbe.y),
            String.format("%.3f", cornerProbe.z),
            selfAabbStr,
            collidingStr,
            provider.hasCollidedDuringPlacement(),
            provider.hasRunMoveTogetherAfterCollision(),
            provider.isMoveTogetherLocked(),
            provider.isSpawnedBackward());
    }

    /**
     * Spawn the contents entities for every enclosed carriage in this
     * group, using the pending records stashed by
     * {@code TrainAssembler.spawnGroup}. Fired exactly once per group, at
     * the moment {@link TrainTransformProvider#markPlacedSuccessfully} flips.
     *
     * <p>Why now: by this point the placement-collision tracker has run
     * {@link #CLEAN_TICKS_FOR_SUCCESS} consecutive non-colliding ticks, so
     * the carriage's {@code spawnWorldPos} (and consequently the world-space
     * position of its shipyard chunks) is stable. Any shipyard-entity mixin
     * binding done by VS at {@code addFreshEntity} time will see the same
     * ship-transform on subsequent ticks — no shift mid-attachment.</p>
     *
     * <p>Race-free: {@code takePendingContentsEntitySpawns} atomically nulls
     * the array on the provider, so a follow-up tick that somehow re-enters
     * the success branch (shouldn't, but defensive) sees null and skips.
     * {@code null} slots in the returned array correspond to FLATBED slots —
     * those have no contents and are skipped without a log line.</p>
     */
    private static void firePendingContentsEntitySpawns(ServerLevel level, TrainTransformProvider provider) {
        PendingContentsEntitySpawn[] pending = provider.takePendingContentsEntitySpawns();
        if (pending == null) return;
        int fired = 0;
        for (PendingContentsEntitySpawn p : pending) {
            if (p == null) continue;
            try {
                CarriagePlacer.applyContentsEntitiesAt(level,
                    p.shipyardOrigin(), p.variant(), p.dims(), p.config(), p.carriageIndex());
                fired++;
            } catch (Throwable t) {
                LOGGER.warn("[DungeonTrain] Deferred contents-entity spawn failed for pIdx={} origin={}: {}",
                    p.carriageIndex(), p.shipyardOrigin(), t.toString());
            }
        }
        LOGGER.info("[DungeonTrain] Placement tracker: fired deferred contents-entity spawn for group anchorPIdx={} ({} of {} slots had pending entities)",
            provider.getPIdx(), fired, pending.length);
    }

    /**
     * Pure decision helper for {@link #runPlacementCollisionTracker}. Package-private
     * for unit tests. Returns the X-axis shift to apply this tick:
     * <ul>
     *   <li>{@code colliding == true} → push AWAY from offender. Offender at
     *       higher pIdx (in front of us) → return {@code -COLLISION_SHIFT_BLOCKS};
     *       offender at lower pIdx (behind us) → return {@code +COLLISION_SHIFT_BLOCKS}.
     *       Equal pIdx is impossible (the collision check skips self), so the
     *       ternary is exhaustive. Collision pushback is never gated by
     *       {@code moveTogetherLocked}.</li>
     *   <li>{@code colliding == false}, {@code moveTogetherLocked == false},
     *       {@code gapToFacingSibling} finite and {@code > MAX_GAP_BLOCKS} →
     *       pull TOWARD the train. Forward-spawn (train at -X) → return
     *       {@code -COLLISION_SHIFT_BLOCKS}; backward-spawn (train at +X) →
     *       return {@code +COLLISION_SHIFT_BLOCKS}.</li>
     *   <li>otherwise → return {@code 0.0} (clean tick; caller increments the
     *       consecutive-clean counter). Move-together is suppressed when the
     *       lock has fired so a clean tick still accumulates and the carriage
     *       can settle.</li>
     * </ul>
     * {@code gapToFacingSibling} is {@link Double#POSITIVE_INFINITY} when no
     * sibling is on the train-facing side (e.g. seed carriage of a fresh
     * train) — falls into the clean branch by the finite-check guard.
     *
     * <p>{@code moveTogetherLocked} reflects per-carriage state owned by
     * {@link TrainTransformProvider}. It flips to {@code true} after the
     * collide → move-together → collide cycle is observed, suppressing further
     * close-gap shifts so the two systems can't keep fighting on the same
     * group.</p>
     */
    static double placementTrackerShiftDx(
        boolean colliding,
        int selfPIdx,
        int collidingPIdx,
        double gapToFacingSibling,
        boolean spawnedBackward,
        boolean moveTogetherLocked
    ) {
        if (colliding) {
            return (collidingPIdx > selfPIdx)
                ? -COLLISION_SHIFT_BLOCKS
                : +COLLISION_SHIFT_BLOCKS;
        }
        if (!moveTogetherLocked
            && Double.isFinite(gapToFacingSibling)
            && gapToFacingSibling > MAX_GAP_BLOCKS) {
            return spawnedBackward
                ? +COLLISION_SHIFT_BLOCKS
                : -COLLISION_SHIFT_BLOCKS;
        }
        return 0.0;
    }

    /**
     * X-axis gap from this carriage's train-facing face to the nearest sibling
     * AABB on that side, in world blocks. Forward spawns measure the LOW-X face
     * (carriage faces train at -X); backward spawns measure the HIGH-X face.
     * Y/Z must overlap (siblings on the same lane).
     *
     * <p>Returns {@link Double#POSITIVE_INFINITY} when no sibling sits on the
     * train-facing side — e.g. the seed carriage of a fresh train, where any
     * pull-toward action would be meaningless. The caller's
     * {@code gap > MAX_GAP_BLOCKS} check short-circuits on infinity via
     * {@link Double#isFinite}.</p>
     *
     * <p>Sibling set: visible train ∪ {@link Trains#knownGroups} registry,
     * deduped by ship id, skipping zero-AABB ships and self. Mirrors
     * {@link #checkOneCarriage} so the gap loop and collision loop draw from
     * the same neighbour set.</p>
     */
    private static double gapToTrainFacingSibling(
        UUID trainId,
        Trains.Carriage self,
        List<Trains.Carriage> train
    ) {
        AABBdc selfAabb = self.ship().worldAABB();
        if (isZeroAabb(selfAabb)) return Double.POSITIVE_INFINITY;
        boolean spawnedBackward = self.provider().isSpawnedBackward();
        double selfMinX = selfAabb.minX(), selfMaxX = selfAabb.maxX();
        double selfMinY = selfAabb.minY(), selfMaxY = selfAabb.maxY();
        double selfMinZ = selfAabb.minZ(), selfMaxZ = selfAabb.maxZ();

        long selfId = self.ship().id();
        Set<Long> seen = new HashSet<>();
        seen.add(selfId);

        double best = Double.POSITIVE_INFINITY;
        for (Trains.Carriage other : train) {
            if (!seen.add(other.ship().id())) continue;
            AABBdc o = other.ship().worldAABB();
            if (isZeroAabb(o)) continue;
            double g = facingGapBetween(
                selfMinX, selfMaxX, selfMinY, selfMaxY, selfMinZ, selfMaxZ,
                o.minX(), o.maxX(), o.minY(), o.maxY(), o.minZ(), o.maxZ(),
                spawnedBackward);
            if (g < best) best = g;
        }
        Map<Integer, ManagedShip> registry = Trains.knownGroups(trainId);
        for (ManagedShip ship : registry.values()) {
            if (!seen.add(ship.id())) continue;
            AABBdc o = ship.worldAABB();
            if (isZeroAabb(o)) continue;
            double g = facingGapBetween(
                selfMinX, selfMaxX, selfMinY, selfMaxY, selfMinZ, selfMaxZ,
                o.minX(), o.maxX(), o.minY(), o.maxY(), o.minZ(), o.maxZ(),
                spawnedBackward);
            if (g < best) best = g;
        }
        return best;
    }

    /**
     * X-axis gap from {@code self}'s train-facing face to {@code other}'s
     * nearest face on that side, in world blocks. Pure primitive-arg helper
     * so it stays testable — {@code AABBdc} lives on
     * {@code additionalRuntimeClasspath} only and isn't on the test classpath.
     *
     * <p>Returns {@link Double#POSITIVE_INFINITY} when {@code other} sits on
     * the wrong side (doesn't qualify as a train-facing neighbour) or doesn't
     * overlap self on Y or Z (not on the same lane).</p>
     *
     * <p>Forward-spawn ({@code spawnedBackward=false}): self.minX is
     * train-facing; candidate must sit with maxX ≤ self.minX. Gap =
     * {@code self.minX − other.maxX}.</p>
     * <p>Backward-spawn ({@code spawnedBackward=true}): self.maxX is
     * train-facing; candidate must sit with minX ≥ self.maxX. Gap =
     * {@code other.minX − self.maxX}.</p>
     */
    static double facingGapBetween(
        double selfMinX, double selfMaxX,
        double selfMinY, double selfMaxY,
        double selfMinZ, double selfMaxZ,
        double otherMinX, double otherMaxX,
        double otherMinY, double otherMaxY,
        double otherMinZ, double otherMaxZ,
        boolean spawnedBackward
    ) {
        // Y/Z lane filter — different vertical or lateral lane carriages do
        // NOT count as neighbours even if they're nearby on X.
        if (!(selfMaxY > otherMinY && selfMinY < otherMaxY)) return Double.POSITIVE_INFINITY;
        if (!(selfMaxZ > otherMinZ && selfMinZ < otherMaxZ)) return Double.POSITIVE_INFINITY;
        if (spawnedBackward) {
            if (otherMinX < selfMaxX) return Double.POSITIVE_INFINITY;
            return otherMinX - selfMaxX;
        } else {
            if (otherMaxX > selfMinX) return Double.POSITIVE_INFINITY;
            return selfMinX - otherMaxX;
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
        BLOCKED_SINCE_FORWARD.clear();
        BLOCKED_SINCE_BACKWARD.clear();
        STALL_WARNED_FORWARD.clear();
        STALL_WARNED_BACKWARD.clear();
        CULL_CLEARED_FORWARD.clear();
        CULL_CLEARED_BACKWARD.clear();
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

    /**
     * Elapsed-tick milestones at which {@link #tickEntityDriftTracking}
     * logs a contents-entity's current world position relative to its
     * requested spawn coords. Bounded by 60 — the placement-tracker's
     * own clean-tick window — so even slow lazy-bind races would show up
     * by the final milestone.
     */
    private static final long[] DRIFT_MILESTONES = { 1L, 5L, 20L, 60L };

    /**
     * In-flight drift-tracking records for contents-spawned entities.
     * Populated by {@link #trackEntityDrift} (called from
     * {@link CarriageContentsPlacer} immediately after a successful
     * {@code addFreshEntity} when
     * {@link games.brennan.dungeontrain.debug.DebugFlags#logContentsEntities}
     * is on). Drained by {@link #tickEntityDriftTracking} once the last
     * milestone (60 ticks) has been logged or the entity is gone.
     *
     * <p>Bounded leak risk: each entry lives at most 60 ticks (~3 s) of
     * server time. {@code ConcurrentHashMap} for belt-and-braces safety
     * against any future off-thread caller — both readers and writers
     * today are on the server thread.</p>
     */
    private static final Map<UUID, EntityDriftTrack> ENTITY_DRIFT_TRACKS = new ConcurrentHashMap<>();

    /**
     * One entry per contents-entity being observed for post-spawn drift.
     * Holds the requested spawn coords (the {@code (worldX, worldY, worldZ)}
     * passed to {@code entity.moveTo}) and the game tick the spawn fired
     * on, so per-tick checks can compute elapsed ticks and per-axis deltas.
     *
     * <p>{@code milestonesLoggedMask} is a 4-bit set, one bit per index in
     * {@link #DRIFT_MILESTONES}. Flipped on by {@link #tickEntityDriftTracking}
     * after logging so duplicate ticks (defensive — shouldn't happen but
     * cheap) cannot double-log the same milestone.</p>
     */
    private static final class EntityDriftTrack {
        final long spawnTick;
        final double spawnX;
        final double spawnY;
        final double spawnZ;
        final int carriagePIdx;
        int milestonesLoggedMask;

        EntityDriftTrack(long spawnTick, double spawnX, double spawnY, double spawnZ, int carriagePIdx) {
            this.spawnTick = spawnTick;
            this.spawnX = spawnX;
            this.spawnY = spawnY;
            this.spawnZ = spawnZ;
            this.carriagePIdx = carriagePIdx;
            this.milestonesLoggedMask = 0;
        }
    }

    /**
     * Register a freshly-spawned contents entity for post-spawn drift
     * observation. Caller must already have confirmed
     * {@code addFreshEntity} returned true; the entity's UUID is the map
     * key.
     *
     * <p>Per-tick checks at {@link #DRIFT_MILESTONES} elapsed ticks log
     * the requested spawn coords, the entity's current world position,
     * and the per-axis delta — telling us whether the entity stayed
     * where we asked, was instantly ejected by vanilla "in solid block"
     * resolution, or drifted during Sable's lazy ship-binding window.
     * </p>
     */
    public static void trackEntityDrift(UUID entityId, long spawnTick,
                                        double spawnX, double spawnY, double spawnZ,
                                        int carriagePIdx) {
        ENTITY_DRIFT_TRACKS.put(entityId,
            new EntityDriftTrack(spawnTick, spawnX, spawnY, spawnZ, carriagePIdx));
    }

    /**
     * Per-tick drift-milestone walker. For each tracked entity, looks up
     * the current world position via {@link ServerLevel#getEntity(UUID)}
     * and logs at the unlogged elapsed-tick milestones in
     * {@link #DRIFT_MILESTONES}. Entries self-evict once the final
     * milestone fires, the entity is gone, or {@code elapsed > 60}.
     *
     * <p>Cheap: only iterates entries we explicitly registered — typically
     * a handful per spawn burst, draining within 60 ticks of any given
     * spawn. Zero cost when no entries are registered (the
     * {@code logContentsEntities} flag gates registration at the call
     * site in {@link CarriageContentsPlacer}).</p>
     */
    private static void tickEntityDriftTracking(ServerLevel level) {
        if (ENTITY_DRIFT_TRACKS.isEmpty()) return;
        long now = level.getGameTime();
        Iterator<Map.Entry<UUID, EntityDriftTrack>> it = ENTITY_DRIFT_TRACKS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, EntityDriftTrack> entry = it.next();
            UUID uuid = entry.getKey();
            EntityDriftTrack track = entry.getValue();
            long elapsed = now - track.spawnTick;
            if (elapsed > DRIFT_MILESTONES[DRIFT_MILESTONES.length - 1]) {
                it.remove();
                continue;
            }
            Entity ent = level.getEntity(uuid);
            if (ent == null) {
                LOGGER.info("[SpawnDrift] pIdx={} uuid={} t=+{} entity GONE (reqPos=({},{},{}))",
                    track.carriagePIdx, uuid, elapsed,
                    String.format("%.3f", track.spawnX),
                    String.format("%.3f", track.spawnY),
                    String.format("%.3f", track.spawnZ));
                it.remove();
                continue;
            }
            for (int i = 0; i < DRIFT_MILESTONES.length; i++) {
                if (elapsed != DRIFT_MILESTONES[i]) continue;
                int bit = 1 << i;
                if ((track.milestonesLoggedMask & bit) != 0) continue;
                track.milestonesLoggedMask |= bit;
                double dx = ent.getX() - track.spawnX;
                double dy = ent.getY() - track.spawnY;
                double dz = ent.getZ() - track.spawnZ;
                LOGGER.info("[SpawnDrift] pIdx={} uuid={} t=+{} reqPos=({},{},{}) curPos=({},{},{}) delta=({},{},{})",
                    track.carriagePIdx, uuid, elapsed,
                    String.format("%.3f", track.spawnX),
                    String.format("%.3f", track.spawnY),
                    String.format("%.3f", track.spawnZ),
                    String.format("%.3f", ent.getX()),
                    String.format("%.3f", ent.getY()),
                    String.format("%.3f", ent.getZ()),
                    String.format("%+.3f", dx),
                    String.format("%+.3f", dy),
                    String.format("%+.3f", dz));
            }
        }
    }

    /**
     * Per-tick player-proximity gate for deferred contents-entity spawns.
     * Walks every group on every loaded train; for each group whose
     * placement has settled ({@link TrainTransformProvider#isPlacedSuccessfully})
     * but whose pending entity array has not yet been consumed, checks
     * whether any player is within {@link #SPAWN_RADIUS_BLOCKS} blocks of
     * the group's current world position. If so, fires
     * {@link #firePendingContentsEntitySpawns} which atomically drains the
     * pending array (so a follow-up tick can't double-fire).
     *
     * <p>Skips trivially when no group has pending spawns — both the
     * isPlacedSuccessfully and hasPendingContentsEntitySpawns short-circuit
     * are cheap volatile reads.</p>
     *
     * <p>Distance is measured from each player to the group's anchor
     * {@link TrainTransformProvider#getCanonicalPos canonicalPos}. The
     * anchor sits at the back-pad-side edge of the group; with a typical
     * 37-block group footprint and a 48-block radius, players approaching
     * either end of the group fire the gate before the carriage reaches
     * their view bubble.</p>
     */
    private static void tickPendingEntitySpawnDistanceGate(ServerLevel level, List<ServerPlayer> players) {
        Map<UUID, List<Trains.Carriage>> trains = Trains.byTrainId(level);
        for (List<Trains.Carriage> train : trains.values()) {
            for (Trains.Carriage carriage : train) {
                TrainTransformProvider provider = carriage.provider();
                if (!provider.isPlacedSuccessfully()) continue;
                if (!provider.hasPendingContentsEntitySpawns()) continue;
                Vector3dc pos = provider.getCanonicalPos();
                if (pos == null) continue;
                double cx = pos.x();
                double cy = pos.y();
                double cz = pos.z();
                boolean inRange = false;
                for (ServerPlayer player : players) {
                    if (player.distanceToSqr(cx, cy, cz) <= SPAWN_RADIUS_SQ) {
                        inRange = true;
                        break;
                    }
                }
                if (inRange) {
                    LOGGER.info("[DungeonTrain] Distance gate: pIdx={} player within {} blocks — firing deferred contents-entity spawn",
                        provider.getPIdx(), SPAWN_RADIUS_BLOCKS);
                    firePendingContentsEntitySpawns(level, provider);
                }
            }
        }
    }

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

        // Player-distance gate for deferred contents-entity spawns. Fires
        // each group's mobs only when a player gets close, eliminating the
        // long wandering window between far-ahead placement and the player
        // walking in.
        tickPendingEntitySpawnDistanceGate(level, players);

        // Contents-entity drift sampling — debug only. Walks any
        // registered entities (gated at registration on logContentsEntities)
        // and logs position at fixed elapsed-tick milestones to expose
        // post-spawn displacement (vanilla ejection vs Sable lazy-bind race).
        tickEntityDriftTracking(level);

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
        BLOCKED_SINCE_FORWARD.keySet().retainAll(trainsTouchedThisTick);
        BLOCKED_SINCE_BACKWARD.keySet().retainAll(trainsTouchedThisTick);
        STALL_WARNED_FORWARD.keySet().retainAll(trainsTouchedThisTick);
        STALL_WARNED_BACKWARD.keySet().retainAll(trainsTouchedThisTick);
        CULL_CLEARED_FORWARD.keySet().retainAll(trainsTouchedThisTick);
        CULL_CLEARED_BACKWARD.keySet().retainAll(trainsTouchedThisTick);
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
        List<ServerPlayer> nearPlayers = new ArrayList<>();
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
            nearPlayers.add(player);
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

        // Proximity-based latch reset: if any near player's pIdx is past
        // the registry's end in a direction, clear that direction's
        // {@link #CULL_CLEARED_FORWARD}/{@link #CULL_CLEARED_BACKWARD}
        // latch and unregister any ghost anchors past the visible train's
        // end. The bounded cull-clear normally only re-arms via a natural
        // placement success — but when EVERY spawn in a direction is
        // being culled (player rode past Sable's plot, or flew there in
        // creative), no placement ever succeeds and the latch would stay
        // set forever. The "player is beyond the registry" condition
        // signals that they've physically reached the end and a fresh
        // spawn at the next anchor is worth attempting.
        //
        // We also drop ghost anchors from the registry — anchors that
        // were spawned, then culled by Sable, and never reloaded. Without
        // this, the next spawn's anchor (computed as
        // {@code registryMin - groupSize} / {@code registryMax + groupSize})
        // would skip past every ghost, producing a visible pIdx gap
        // between the actual visible end and the next spawn. After
        // cleanup, the registry edge matches the visible edge so the next
        // spawn fills the slot adjacent to what the player can see.
        boolean refreshedAnchors = false;
        for (int playerPIdx : nearPlayerPIdxs) {
            if (playerPIdx > trainMaxAnchor && CULL_CLEARED_FORWARD.remove(trainId) != null) {
                refreshedAnchors |= cleanupGhostAnchors(level, trainId, train, true);
            }
            if (playerPIdx < trainMinAnchor && CULL_CLEARED_BACKWARD.remove(trainId) != null) {
                refreshedAnchors |= cleanupGhostAnchors(level, trainId, train, false);
            }
        }
        if (refreshedAnchors) {
            knownAnchors = Trains.knownAnchors(trainId);
            if (!knownAnchors.isEmpty()) {
                int maxA = Integer.MIN_VALUE;
                int minA = Integer.MAX_VALUE;
                for (int a : knownAnchors) {
                    if (a > maxA) maxA = a;
                    if (a < minA) minA = a;
                }
                trainMaxAnchor = maxA;
                trainMinAnchor = minA;
            }
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
        // Auto-mode forward and backward use the same symmetric trigger:
        // only fire when the player's needed pIdx range actually extends
        // past the registry's current extent in that direction. Forward
        // was previously unconditional ({@code needsForward = true}),
        // relying on the placement-success gate's natural 60-tick rate
        // limit. That rate limit disappears once
        // {@link #isLanePlacementGateClear} starts clearing the lane on
        // a Sable cull (the lane re-opens within one tick of a cull
        // instead of after 60 clean ticks), so without the symmetric
        // needs-check the appender would spawn unboundedly forward when
        // a far-ahead carriage gets repeatedly culled.
        boolean needsForward = MANUAL_MODE
            || (globalMaxNeededPIdx != Integer.MIN_VALUE
                && globalMaxNeededPIdx > trainMaxAnchor);
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
        boolean didForwardSpawn = false;
        boolean didBackwardSpawn = false;

        if (needsForward && isLanePlacementGateClear(LAST_SPAWNED_SHIP_FORWARD, LAST_SPAWNED_TICK_FORWARD, CULL_CLEARED_FORWARD, trainId, train, now, true)) {
            ManagedShip newShip = spawnNewGroup(level, lead, forwardAnchor, groupSize, dims, velocity, trainId, train);
            if (newShip.getKinematicDriver() instanceof TrainTransformProvider newProvider) {
                newProvider.setSpawnedBackward(false);
            }
            LAST_SPAWNED_SHIP_FORWARD.put(trainId, newShip);
            LAST_SPAWNED_TICK_FORWARD.put(trainId, now);
            recordPostSpawnCollisionCheck(trainId, newShip, forwardAnchor, train);
            announceSpawn(level, forwardAnchor);
            didForwardSpawn = true;
        }

        if (needsBackward && isLanePlacementGateClear(LAST_SPAWNED_SHIP_BACKWARD, LAST_SPAWNED_TICK_BACKWARD, CULL_CLEARED_BACKWARD, trainId, train, now, false)) {
            ManagedShip newShip = spawnNewGroup(level, tail, backwardAnchor, groupSize, dims, velocity, trainId, train);
            if (newShip.getKinematicDriver() instanceof TrainTransformProvider newProvider) {
                newProvider.setSpawnedBackward(true);
            }
            LAST_SPAWNED_SHIP_BACKWARD.put(trainId, newShip);
            LAST_SPAWNED_TICK_BACKWARD.put(trainId, now);
            recordPostSpawnCollisionCheck(trainId, newShip, backwardAnchor, train);
            announceSpawn(level, backwardAnchor);
            didBackwardSpawn = true;
        }

        if (STALL_DETECTION_ENABLED) {
            detectAndAnnounceStall(level, trainId, nearPlayers, now,
                needsForward, didForwardSpawn, true,
                BLOCKED_SINCE_FORWARD, STALL_WARNED_FORWARD);
            detectAndAnnounceStall(level, trainId, nearPlayers, now,
                needsBackward, didBackwardSpawn, false,
                BLOCKED_SINCE_BACKWARD, STALL_WARNED_BACKWARD);
        }

        return didForwardSpawn || didBackwardSpawn;
    }

    /**
     * Per-direction placement-success gate. Returns {@code true} iff the
     * direction's previous spawn (if any) has transitioned to
     * {@code placedSuccessfully = true} on its
     * {@link TrainTransformProvider}. Removes the entry from {@code lane}
     * once cleared so the gate's "hot" set stays bounded to in-flight ships.
     *
     * <p>Also clears the lane when Sable has culled the pending sub-level
     * before placement could complete. {@link #runPlacementCollisionTracker}
     * only iterates sub-levels currently in {@code Shipyards.findAll()}, so
     * once a pending carriage falls out of Sable's plot its
     * {@code placedSuccessfully} flag can never flip — without this check the
     * lane would stay closed for the rest of the session.</p>
     *
     * <p>The cull check is gated by {@link #CULL_DETECTION_GRACE_TICKS}.
     * Sable doesn't include a freshly-spawned sub-level in {@code findAll()}
     * for several ticks after creation, so without the grace window the
     * check would false-positive on every new spawn and produce a runaway
     * sequence of gap-creating spawns. After the grace window, if the
     * pending {@code subLevelId} is still missing from {@code currentTrain}
     * (built upstream from {@code Shipyards.findAll()} via
     * {@link Trains#byTrainId}), we log a {@code WARN} and drop the entry
     * so the lane reopens.</p>
     *
     * <p>Cull-clear is bounded by {@code cullClearedFlags}: at most ONE
     * cull-clear per natural placement success. Once the latch fires, the
     * gate stays closed in this direction even after we remove the pending
     * sub-level — extension halts until a future spawn's
     * {@code placedSuccessfully} flips through the normal tracker path, at
     * which point we clear the latch (the train has caught up to the player
     * and Sable's plot covers the train's end). This prevents the runaway
     * cull→clear→spawn→cull cascade where each cull-cleared anchor advances
     * the registry frontier without filling in, producing a long chain of
     * registered-but-invisible ghost carriages.</p>
     */
    private static boolean isLanePlacementGateClear(
        Map<UUID, ManagedShip> lane,
        Map<UUID, Long> laneTickMap,
        Map<UUID, Boolean> cullClearedFlags,
        UUID trainId,
        List<Trains.Carriage> currentTrain,
        long now,
        boolean forward
    ) {
        ManagedShip pending = lane.get(trainId);
        if (pending == null) {
            return !cullClearedFlags.getOrDefault(trainId, false);
        }
        if (pending.getKinematicDriver() instanceof TrainTransformProvider provider
            && !provider.isPlacedSuccessfully()) {
            Long spawnTick = laneTickMap.get(trainId);
            if (spawnTick != null && now - spawnTick < CULL_DETECTION_GRACE_TICKS) {
                return false;
            }
            UUID pendingSubLevelId = pending.subLevelId();
            boolean stillLoaded = false;
            for (Trains.Carriage c : currentTrain) {
                if (c.ship().subLevelId().equals(pendingSubLevelId)) {
                    stillLoaded = true;
                    break;
                }
            }
            if (stillLoaded) {
                return false;
            }
            if (cullClearedFlags.getOrDefault(trainId, false)) {
                return false;
            }
            long ticksSinceSpawn = (spawnTick == null) ? -1L : (now - spawnTick);
            LOGGER.warn(
                "[DungeonTrain] Lane {} pending sub-level {} culled by Sable before placement (ticksSinceSpawn={}) — clearing gate ONCE; further extension paused until next placement succeeds (trainId={})",
                forward ? "forward" : "backward",
                pendingSubLevelId,
                ticksSinceSpawn,
                trainId);
            cullClearedFlags.put(trainId, true);
            lane.remove(trainId);
            return true;
        }
        lane.remove(trainId);
        cullClearedFlags.remove(trainId);
        return true;
    }

    /**
     * Drop registry entries for "ghost" anchors past the visible end of
     * the train in the given direction, AND mark their underlying Sable
     * sub-levels as removed so a future plot move can't reload them
     * into the spot we're about to refill.
     *
     * <p>Called from {@link #updateTrain} when a proximity unlatch fires
     * — the player has physically reached the registry's edge, so any
     * anchor in the registry that's still not in {@code currentTrain}
     * (i.e. Sable culled it long ago and hasn't reloaded it) is treated
     * as a ghost and forgotten. Without this cleanup the next spawn
     * anchor would be placed past the ghosts, leaving a visible pIdx
     * gap between the actual visible end of the train and the new spawn.</p>
     *
     * <p>Sable distinguishes {@code UNLOADED} (culled, retained in
     * {@code HoldingSubLevel} storage, may reload when the plot returns)
     * from {@code REMOVED} (gone for good). A culled ghost is still in
     * Sable's storage; if we only forgot it from our registry, Sable
     * could later reload it into the same anchor we just respawned —
     * producing two ships at the same anchor. {@link Shipyard#delete}
     * calls {@code SubLevel.markRemoved()} which the container's tick
     * pass converts into a {@code REMOVED} removal, so the holding-
     * storage entry is also dropped.</p>
     *
     * <p>Returns {@code true} if at least one anchor was removed so the
     * caller can recompute {@code trainMin/MaxAnchor} from the updated
     * registry before proceeding with the spawn decision.</p>
     */
    private static boolean cleanupGhostAnchors(
        ServerLevel level,
        UUID trainId,
        List<Trains.Carriage> currentTrain,
        boolean forward
    ) {
        int visibleMin = Integer.MAX_VALUE;
        int visibleMax = Integer.MIN_VALUE;
        for (Trains.Carriage c : currentTrain) {
            int p = c.provider().getPIdx();
            if (p < visibleMin) visibleMin = p;
            if (p > visibleMax) visibleMax = p;
        }
        if (visibleMin == Integer.MAX_VALUE) return false;

        Set<Integer> known = Trains.knownAnchors(trainId);
        int finalVisibleMin = visibleMin;
        int finalVisibleMax = visibleMax;
        java.util.List<Integer> toRemove = new java.util.ArrayList<>();
        for (int a : known) {
            boolean past = forward ? (a > finalVisibleMax) : (a < finalVisibleMin);
            if (past) toRemove.add(a);
        }
        if (toRemove.isEmpty()) return false;
        Shipyard shipyard = Shipyards.of(level);
        int deletedSableShips = 0;
        for (int a : toRemove) {
            ManagedShip ship = Trains.unregisterGroup(trainId, a);
            if (ship != null) {
                shipyard.delete(ship);
                deletedSableShips++;
            }
        }
        LOGGER.info(
            "[DungeonTrain] Cleaned up {} ghost anchor(s) past visible {}={} for trainId={} — anchors={}, sableShipsDeleted={}",
            toRemove.size(),
            forward ? "max" : "min",
            forward ? visibleMax : visibleMin,
            trainId,
            toRemove,
            deletedSableShips);
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
     * Per-direction stall detection — Phase 1 diagnostic. Called once per
     * direction at the end of {@link #updateTrain}, after the spawn-attempt
     * branches. Tracks how long the appender has wanted to spawn in this
     * direction without succeeding, and emits one chat warning + one
     * {@code LOGGER.warn} when the duration first crosses
     * {@link #STUCK_THRESHOLD_TICKS}. The one-shot warning latch
     * ({@code warnedMap}) prevents repeating the message every tick once
     * the threshold is crossed; it clears when a spawn finally fires or
     * the direction stops being needed.
     *
     * <p>Skipped in {@link #MANUAL_MODE} — manual mode only spawns on
     * J-press, so "no spawn" is the expected state, not a stall.</p>
     */
    private static void detectAndAnnounceStall(
        ServerLevel level,
        UUID trainId,
        List<ServerPlayer> nearPlayers,
        long now,
        boolean directionNeeded,
        boolean spawnedThisTick,
        boolean forward,
        Map<UUID, Long> blockedSinceMap,
        Map<UUID, Boolean> warnedMap
    ) {
        if (MANUAL_MODE) {
            blockedSinceMap.remove(trainId);
            warnedMap.remove(trainId);
            return;
        }
        if (!directionNeeded || spawnedThisTick) {
            blockedSinceMap.remove(trainId);
            warnedMap.remove(trainId);
            return;
        }
        long blockedSince = blockedSinceMap.computeIfAbsent(trainId, k -> now);
        long ticksStuck = now - blockedSince;
        if (ticksStuck < STUCK_THRESHOLD_TICKS) return;
        if (warnedMap.getOrDefault(trainId, false)) return;

        String shortId = trainId.toString().substring(0, 8);
        String dir = forward ? "forward" : "backward";
        String msg = "[DungeonTrain] STALL: train " + shortId
            + " has not spawned " + dir + " for "
            + ticksStuck + " ticks (" + (ticksStuck / 20) + "s)";
        LOGGER.warn(msg);
        if (games.brennan.dungeontrain.debug.DebugFlags.chatStallTrain()) {
            for (ServerPlayer player : nearPlayers) {
                player.displayClientMessage(
                    Component.literal(msg).withStyle(ChatFormatting.YELLOW),
                    false);
            }
        }
        warnedMap.put(trainId, true);
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
