package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.SableConfig;
import games.brennan.dungeontrain.bootstrap.BootstrapProgress;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.debug.DebugAccessEvents;
import games.brennan.dungeontrain.net.CarriageIndexPacket;
import games.brennan.dungeontrain.net.TrainDebugCarriagePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.ship.sable.PhysicsFreeze;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.ship.sable.WorldgenForceGuard;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.StartingDimension;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
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
 * Assembly places the group's blocks once, so appending never disturbs an
 * existing group's MassTracker / rotationPoint. Later block changes do move it,
 * and are corrected by
 * {@link games.brennan.dungeontrain.ship.sable.CarriagePivotPin}.</p>
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
     * Last carriage index sent to each player for the F3+4 debug panel. Separate from
     * {@link #LAST_SENT_PIDX} because the two are computed in different frames and cross their
     * boundaries at different moments — sharing one record would swallow debug updates.
     */
    private static final Map<UUID, Integer> LAST_SENT_DEBUG_PIDX = new ConcurrentHashMap<>();

    /**
     * The carriage index last pushed to {@code playerId}'s HUD — the same value the
     * "Carriage:" read-out shows — or {@code null} when the player isn't currently
     * tracked near a train. Lets server-thread callers (e.g. the Discord advancement
     * embed) report the exact carriage the HUD displays. Read on the server thread.
     */
    public static Integer lastCarriageIndex(UUID playerId) {
        return LAST_SENT_PIDX.get(playerId);
    }

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
    private static final Map<UUID, Long> CULL_CLEARED_FORWARD = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> CULL_CLEARED_BACKWARD = new ConcurrentHashMap<>();

    /**
     * Per-train, per-direction (option 2): the game tick at which the
     * registry-edge reference first failed to resolve in this direction (it was
     * neither visible, held-and-being-reloaded, nor a live registry wrapper).
     * Cleared the moment the edge resolves to a spawnable reference. Drives the
     * throttled {@link #EDGE_UNRESOLVED_WARN_TICKS} WARN below — purely
     * diagnostic, never gates behaviour (resolution self-heals as the edge
     * surfaces / reloads).
     */
    private static final Map<UUID, Long> EDGE_UNRESOLVED_SINCE_FORWARD = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> EDGE_UNRESOLVED_SINCE_BACKWARD = new ConcurrentHashMap<>();
    /** One-shot WARN latch per direction; reset alongside {@code EDGE_UNRESOLVED_SINCE_*}. */
    private static final Map<UUID, Boolean> EDGE_UNRESOLVED_WARNED_FORWARD = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> EDGE_UNRESOLVED_WARNED_BACKWARD = new ConcurrentHashMap<>();
    /**
     * Per-train, per-direction: the sub-level ID Dungeon Train last issued a
     * reload-from-holding for. A held edge sits in the {@code RELOAD_DEFER} state
     * for the whole ~200-tick surfacing window, but {@link Shipyard#reloadFromHolding}
     * is a no-op there (the entry lives in Sable's global holding map yet is absent
     * from the chunk's map, so its {@code snatchAndLoad} snatches nothing) — and
     * each call makes Sable log its benign "wasn't present in the holding chunk"
     * ERROR 1:1. Recovery is the trailing force-load window + {@code findAll}, not
     * this call, so we issue it only ONCE per held-edge episode: re-armed when the
     * edge changes (new uuid), cleared on resolve alongside {@code EDGE_UNRESOLVED_*}.
     * Collapses thousands of no-op calls (and their ERROR spam) to a handful.
     */
    private static final Map<UUID, UUID> RELOAD_ISSUED_FORWARD = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> RELOAD_ISSUED_BACKWARD = new ConcurrentHashMap<>();
    /**
     * Ticks a registry edge may stay unresolved before one WARN fires. 200 ≈
     * 10 s — comfortably past the few-tick {@code findAll} surfacing lag and the
     * holding-reload round-trip, so only a genuinely stuck edge trips it.
     */
    private static final long EDGE_UNRESOLVED_WARN_TICKS = 200L;

    /**
     * Per-train set of sub-level IDs that Dungeon Train currently force-loads
     * (the trailing-segment window). Maintained each tick by
     * {@link #maintainTrailingForceLoadWindow} via add/release reconciliation,
     * so it always mirrors the live Sable tickets we own. Cleared in
     * {@link #clearSettleTracker}; an entry is removed when its set empties.
     */
    private static final Map<UUID, Set<UUID>> FORCELOADED_BY_TRAIN = new ConcurrentHashMap<>();

    /**
     * trainId → game-tick (inclusive) until which the "player left vicinity" walk-away
     * release is suppressed after a singleplayer pause/resume. Set by
     * {@link #grantResumeGrace} (called from {@code ResumeWatchdog} on a detected resume);
     * consulted in {@link #updateTrain}'s empty-near-players bail so a momentarily flung-off
     * rider can't lose the train to a Sable cull before re-anchoring (#547/#548). While
     * active it also keeps the whole-train resume-hold ({@link #holdWholeTrainForResume})
     * resident. Renewed each tick the rider is still not near (capped via
     * {@link #RESUME_STARTED_TICK}); entries self-expire and are cleared in
     * {@link #clearSettleTracker}.
     */
    private static final Map<UUID, Long> RESUME_GRACE_UNTIL_TICK = new ConcurrentHashMap<>();

    /**
     * trainId → game-tick the current resume-recovery began (set by
     * {@link #grantResumeGrace}). Bounds how long {@link #updateTrain}'s bail keeps
     * renewing {@link #RESUME_GRACE_UNTIL_TICK} while the rider is still not near — so a
     * genuine post-resume walk-away eventually releases instead of holding forever.
     * Cleared when the grace lapses or in {@link #clearSettleTracker}.
     */
    private static final Map<UUID, Long> RESUME_STARTED_TICK = new ConcurrentHashMap<>();

    /** Ticks the resume bail re-extends the grace by each tick the rider is still not near (~3 s). */
    private static final int RESUME_GRACE_RENEW_TICKS = 60;

    /**
     * Hard cap (ticks ≈ 10 s) on the renewed resume-hold, measured from the resume
     * start. Past this the bail stops renewing and releases — covers "Sable carry never
     * recovers" and "player genuinely walked off right after a resume" without an
     * unbounded force-load hold.
     */
    private static final int RESUME_HOLD_CAP_TICKS = 200;

    /**
     * Suppress the "player left vicinity" force-load release for {@code trainId} until
     * {@code nowTick + graceTicks}, and record {@code nowTick} as the resume-recovery
     * start (the renewal cap in {@link #updateTrain}'s bail measures from it). Called by
     * {@code ResumeWatchdog} the moment it detects a singleplayer pause/resume, so the
     * whole-train resume-hold ({@link #holdWholeTrainForResume}) stays resident while the
     * flung-off rider re-anchors (#547/#548). Server-thread only.
     */
    public static void grantResumeGrace(UUID trainId, long nowTick, int graceTicks) {
        RESUME_GRACE_UNTIL_TICK.put(trainId, nowTick + graceTicks);
        // ResumeWatchdog only calls this on the resume-DETECTION tick (the >=2 s gap),
        // never mid-recovery, so each call marks a fresh recovery start — overwrite rather
        // than putIfAbsent so a later resume isn't capped from a stale earlier start.
        RESUME_STARTED_TICK.put(trainId, nowTick);
    }

    /** True while {@code trainId} is inside its (renewing) resume-recovery grace window. */
    private static boolean isResumeHoldActive(UUID trainId, long nowTick) {
        return withinResumeGrace(RESUME_GRACE_UNTIL_TICK.get(trainId), nowTick);
    }

    /**
     * Pure core: is the resume-grace window still open at {@code nowTick}? The window is open
     * (inclusive) up to and including its deadline tick; a {@code null} deadline means no grace
     * was ever granted (a normal walk-away, not a pause/resume). Package-private + pure for unit
     * testing alongside {@link #shouldRetainOnWalkAway} / {@link #decideEdgeAction}.
     */
    static boolean withinResumeGrace(Long graceUntilTick, long nowTick) {
        return graceUntilTick != null && nowTick <= graceUntilTick;
    }

    /**
     * Pure core: may {@link #updateTrain}'s bail still renew the grace this tick? Only while the
     * recovery hasn't reached its hard cap (measured from the resume start), so a genuine
     * post-resume walk-away eventually stops renewing and releases instead of holding forever. A
     * {@code null} start means no active recovery — never renew.
     */
    static boolean shouldRenewResumeGrace(Long startedAtTick, long nowTick, int capTicks) {
        return startedAtTick != null && nowTick - startedAtTick < capTicks;
    }

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
     * Opt-in master switch for the backward-seam-gap diagnostic probes
     * ({@code [seamgap]} / {@code [bwd-place]} / {@code [anchor-div]} /
     * {@code [capture-lag]}). Off by default — flip via
     * {@code /dungeontrain debug seamgap-trace on} during a backward-ride
     * test session to capture the per-seam world-gap-vs-pIdx time series used
     * to diagnose the post-#519 growing-gap regression. When off, every probe
     * is a no-op (no extra {@link Trains#byTrainId} scan, no log lines), so the
     * spawn loop runs bit-identical to the non-instrumented build.
     */
    private static volatile boolean SEAMGAP_TRACE_ENABLED = false;

    /** @see #SEAMGAP_TRACE_ENABLED */
    public static boolean isSeamGapTraceEnabled() {
        return SEAMGAP_TRACE_ENABLED;
    }

    /** Toggle the backward-seam-gap diagnostic probes. Server thread only. */
    public static void setSeamGapTraceEnabled(boolean enabled) {
        SEAMGAP_TRACE_ENABLED = enabled;
    }

    /**
     * Sample cadence for the periodic {@code [seamgap]} and {@code [anchor-div]}
     * probes: once every 20 game ticks (≈1 s at 20 Hz) when
     * {@link #SEAMGAP_TRACE_ENABLED} is on. Keeps the log readable over a
     * multi-minute ride while still resolving how the gap grows over time.
     */
    private static final int SEAMGAP_SAMPLE_PERIOD_TICKS = 20;

    /**
     * Max synchronous full shared-carriage captures per ghost-anchor cull pass. A full capture (up to
     * ~700 KB voxel read) runs on the server thread; capping it bounds a whole-rake cull's tick hitch.
     * Overflow carriages get a bare lease-return instead — at most the last sub-second of un-flushed
     * edits is left to the deltas already streamed to the relay. Only carriages with un-flushed edits
     * ever capture at all (a streamed carriage needs none), so this cap is rarely reached.
     */
    private static final int MAX_SHARED_CAPTURES_PER_CULL = 8;


    /**
     * Minimum visible gap (in blocks) between a freshly-spawned group and
     * its reference. Sable's collision broad-phase considers narrow gaps
     * as contact and reacts violently — at 0.1 blocks we observed groups
     * that landed near the floor jittering by 10-20 blocks per physics
     * tick, with overlapping blocks getting destroyed (smoke particles)
     * and the emptied sub-level disposed by Sable. 0.3 leaves enough slack
     * for the broad-phase even when reference-frame drift produces sub-1-block
     * stride errors. An in-game trial at 0.15 reintroduced seam jitter, so
     * the minimum stays at 0.3. Visible gap range becomes [0.3, 1.3] blocks at
     * spawn, held inside the dead-band [0.3, 0.9] by the placement tracker.
     *
     * <p>This is the LOWER bound the per-tick tracker actively maintains: a
     * carriage whose real {@code worldAABB} edge gap falls below it is shifted
     * away from its train-facing sibling ({@link #placementTrackerShiftDx}) and
     * its clean-tick counter resets, so a carriage can never accumulate its
     * {@link #CLEAN_TICKS_FOR_SUCCESS} run — and thus never
     * {@code placedSuccessfully} — while closer than this. Without that floor
     * the tracker counted any non-overlapping gap as clean, letting carriages
     * settle and permanently place a hair from their neighbour.</p>
     */
    static final double MIN_GAP_BLOCKS = 0.3;

    /**
     * Chunk pre-warm reach bias (in blocks) used by {@link #eagerFillForBootstrap}
     * to size how far ahead the eager-fill footprint reaches when pre-generating
     * chunks. NO LONGER controls the visible seam gap — that is now
     * {@link #TARGET_EAGER_GAP_BLOCKS}, injected as a fractional world-space
     * offset. Kept as the reach bias because {@code ceil}-ing it gives a safe
     * (≥ target) per-group over-estimate for the pre-warm bounds.
     */
    private static final double EAGER_FILL_GAP_BLOCKS = 0.5;

    /**
     * Target visible gap (in blocks) between adjacent carriage GROUPS produced
     * by {@link #eagerFillForBootstrap} — the common path most players see.
     *
     * <p>Eager-fill commits each group to an integer {@code BlockPos} origin, so
     * a whole-block gap (≥1) is the finest it can express directly. To get a
     * sub-block gap the blocks are placed at the nearest integer and the
     * leftover fraction is carried into the group's world transform via
     * {@link TrainTransformProvider#preSeedSpawnShiftX} (a double
     * {@code spawnWorldPos}), chained through true world X so per-group offsets
     * don't cancel. Result: every seam settles at exactly this value.</p>
     *
     * <p>MUST stay ≥ the ~0.3 Sable broad-phase floor documented on
     * {@link #MIN_GAP_BLOCKS} — below it the runtime broad-phase treats
     * neighbours as touching and the train jitters. 0.4 is safely above the
     * floor while ~60% tighter than the previous ~1.0-block eager-fill seam.</p>
     */
    private static final double TARGET_EAGER_GAP_BLOCKS = 0.4;

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
     * {@link #spawnPlannedGroup} we run an AABB-vs-AABB intersection between
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
     * How long a cull-clear latch (see {@link #isLanePlacementGateClear}) may hold its lane shut
     * before it expires and the lane is allowed one more attempt. 600 ticks = 30 s.
     *
     * <p>The latch exists to stop a runaway {@code cull → clear → spawn → cull} cascade, and it is
     * only lifted by a later spawn in that direction reaching {@code placedSuccessfully} — which can
     * never happen while the latch itself keeps the lane shut. Without an expiry the only escape was
     * the player physically walking past the registry's edge anchor, so a single ill-timed Sable cull
     * could halt backward generation for the rest of the session: the train simply ended. Expiry
     * restores forward progress while keeping the cascade bounded to one speculative spawn per
     * window per direction instead of one per tick.</p>
     */
    static final long CULL_LATCH_EXPIRY_TICKS = 600L;

    /**
     * How many trailing GROUPS (nearest the tail) Dungeon Train force-loads.
     * Sable culls any sub-level whose world chunks leave the player-centred
     * simulation bubble; the backmost carriages of a backward-riding train do
     * exactly that and get culled before the {@link #CLEAN_TICKS_FOR_SUCCESS}
     * settle completes (the {@code backward-generation-stall}). Force-loading
     * the active frontier keeps it resident and ticking long enough to settle,
     * AND keeps the settling carriage's predecessors stable so it doesn't
     * collide with a culled-then-reloaded (drifted) neighbour.
     *
     * <p>The train list is one entry per group/sub-level, so this is a count of
     * <em>sub-levels</em>. Bounded and small so retained memory stays O(groups),
     * not O(train length): 4 covers the newest backward group plus the three
     * settled predecessors a new spawn places against.</p>
     */
    private static final int TRAILING_FORCELOAD_GROUPS = 4;


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
    // Package-private rather than private so ContentsDespawnController can ALIAS it as its restore
    // radius instead of repeating 48.0. The despawn gate is the mirror image of this one, and a
    // player walking up to a carriage should see the same distance behaviour whether the contents
    // are being spawned for the first time or restored after a sweep.
    static final double SPAWN_RADIUS_BLOCKS = 48.0;
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
     * Game tick of the last placement shift applied to a carriage, keyed by
     * sub-level id. Used to throttle shifts (see {@link #SHIFT_SETTLE_TICKS}).
     */
    private static final Map<UUID, Long> PLACEMENT_TRACKER_LAST_SHIFT = new ConcurrentHashMap<>();

    /**
     * Consecutive ticks each unplaced sub-level has read a gap past
     * {@link #LARGE_GAP_REPLACE_BLOCKS}. Reset the moment a reading comes back inside the nudgeable
     * range, so only a sustained separation triggers the one-step re-place.
     */
    private static final Map<UUID, Integer> PLACEMENT_TRACKER_LARGE_GAP_TICKS = new ConcurrentHashMap<>();

    /**
     * One catch-up burst's groups, linked so they settle as a RIGID UNIT:
     * sub-level id → the groups {@link #planChainedSpawn} chained directly off
     * it in the same tick. A burst of N groups forms a chain (leader → first
     * follower → …), not a star, so a shift walks down it.
     *
     * <p><b>Why this must exist.</b> A burst puts two UNPLACED groups next to
     * each other, and {@link #runPlacementCollisionTracker} treats every
     * carriage independently. When the leader shifts to correct its seam
     * against the old end of the train, the follower — spawned at exactly
     * {@link #TARGET_GAP_BLOCKS} from it — would stay put, and the whole shift
     * would be taken out of the leader/follower seam instead: a shift toward
     * the follower drives 0.4 to −0.1, i.e. an overlap under Sable's ~0.3
     * broad-phase floor (jitter → smoke → disposal), and a shift away leaves
     * the follower chasing it across settle windows. Before the burst this
     * could not happen — only one group per lane was ever unplaced at a time.
     *
     * <p>Propagating the identical {@code dx} keeps the intra-burst seam at
     * exactly the value it was planned with, so the follower's own tracker pass
     * reads clean and never counter-shifts. The spawn-time
     * {@link #adjustForCollisions} move needs no such handling — the chained
     * plan is derived from the leader's already-adjusted origin.</p>
     *
     * <p>Lifetime mirrors the other {@code PLACEMENT_TRACKER_*} maps: entries
     * go when a group is placed (or force-finalised — a settled group never
     * shifts again, so there is nothing left to propagate), on the per-tick
     * reconciliation against live sub-levels, and on state reset.</p>
     */
    private static final Map<UUID, List<BurstFollower>> BURST_FOLLOWERS = new ConcurrentHashMap<>();

    /** A group chained onto another by a catch-up burst — see {@link #BURST_FOLLOWERS}. */
    private record BurstFollower(UUID subLevelId, TrainTransformProvider provider) {}

    /**
     * Reverse index of {@link #BURST_FOLLOWERS}: follower sub-level id → the
     * group it is chained off. Present exactly while that link is alive, which
     * is what {@link #isBurstFollower} reads.
     */
    private static final Map<UUID, UUID> BURST_FOLLOWER_OF = new ConcurrentHashMap<>();

    /**
     * Ticks the tracker must wait after shifting a carriage before it may shift
     * it again. The gap it reads comes from {@link ManagedShip#worldAABB()},
     * which lags a shift by a tick or two (Sable applies the new kinematic pose
     * next tick, and the AABB reflects it after that). Without this throttle the
     * tracker re-reads a STALE gap and stacks a second shift before the first is
     * visible, so a single logical 0.5-block correction lands as ~1.0 — clear
     * across the 0.6-wide dead-band to the far side. The carriage then bounces
     * 0.0↔1.0 forever, never accumulating {@link #CLEAN_TICKS_FOR_SUCCESS}, and
     * the safety valve eventually freezes it at whatever (often touching) phase
     * it is in. Waiting for the AABB to catch up lets each 0.5 shift land inside
     * the band, so the carriage settles in one or two corrections. Chosen ≥ the
     * observed AABB lag with margin; still far under the settle budget.
     */
    private static final int SHIFT_SETTLE_TICKS = 4;

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
     * Upper edge of the placement dead-band. A not-yet-placed carriage whose
     * real edge gap sits inside [{@link #MIN_GAP_BLOCKS}, {@code MAX_GAP_BLOCKS}]
     * = [0.3, 0.5] counts as clean; outside it the per-tick tracker shifts the
     * carriage PROPORTIONALLY toward the band centre {@link #TARGET_GAP_BLOCKS}
     * (see {@link #placementTrackerShiftDx}) and resets the clean-tick counter.
     *
     * <p>Tightened from 0.9 to 0.5 so seams settle small and consistent (~0.4)
     * instead of anywhere in a 0.6-wide band. The band is now NARROWER than the
     * {@link #COLLISION_SHIFT_BLOCKS} (0.5) step, so a fixed-size shift would
     * overshoot it and bounce — which is exactly why the shift is now
     * proportional (magnitude = min(step, |gap − target|)): it lands on the
     * target in one move and, with the shift cooldown, converges without
     * oscillating.</p>
     */
    static final double MAX_GAP_BLOCKS = 0.5;

    /**
     * Target seam gap the placement tracker converges every carriage toward —
     * the centre of the dead-band [{@link #MIN_GAP_BLOCKS}, {@link #MAX_GAP_BLOCKS}].
     * Proportional shifts aim here so gaps end up small and uniform (~0.4) while
     * staying above the ~0.3 Sable broad-phase floor (no touching).
     */
    static final double TARGET_GAP_BLOCKS =
        (MIN_GAP_BLOCKS + MAX_GAP_BLOCKS) / 2.0;

    /**
     * A seam gap this wide is no longer a settling error — it is a group that has fallen out of the
     * train, and nudging cannot bring it back.
     *
     * <p>The tracker's shift is capped at {@link #COLLISION_SHIFT_BLOCKS} once per
     * {@link #SHIFT_SETTLE_TICKS}, i.e. 0.125 blocks/tick, so within the
     * {@link #MAX_PLACEMENT_SETTLE_TICKS} budget it can close at most ~25 blocks — and only by
     * spending the entire budget doing it. Observed live on a cold-generation world: a freshly
     * appended group's physics was starved for ~5 s while the train kept moving, it ended up 67.5
     * blocks behind its real neighbour, and the tracker chased at 0.5 a shift until the safety valve
     * fired 200 ticks later, leaving the hole. 4 blocks sits well clear of the worst legitimate
     * spawn offset (~1.6, when the collision pass moves the origin and the sub-block pre-seed is
     * dropped) and far below what nudging could ever recover, so anything past it is pathological
     * by construction.</p>
     */
    static final double LARGE_GAP_REPLACE_BLOCKS = 4.0;

    /**
     * Consecutive ticks a gap must read past {@link #LARGE_GAP_REPLACE_BLOCKS} before the group is
     * re-placed in one step. A single frame of stale geometry must never teleport a carriage; five
     * ticks of agreement means the separation is real. Frozen bodies never reach this code (the
     * tracker skips them) and absent/degenerate neighbours read as infinite, so the remaining
     * stale-read surface is small — this is belt and braces over it.
     */
    static final int LARGE_GAP_CONFIRM_TICKS = 5;

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
                    PLACEMENT_TRACKER_LAST_SHIFT.remove(subLevelId);
                    // A settled group never shifts again — nothing left to propagate.
                    forgetBurstFollowers(subLevelId);
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

                // Frozen body ⇒ the settle clock stops. A DT-frozen carriage (#646 soft-freeze) stops
                // receiving its per-tick teleport, so its worldAABB is stuck: every collision/gap
                // reading below would be a stale re-read of the same frame, no shift could ever land,
                // and the tracker would nudge spawnWorldPos blind until the safety valve fired —
                // banking tens of blocks of offset that snap into a wide seam on unfreeze.
                // {@link PhysicsFreezeController} now exempts unplaced carriages outright, so this is
                // defence in depth for any other reason a body stops moving (non-resident ship, Sable
                // never firing the first physics tick). Roll firstSeen forward so the frozen ticks
                // don't count toward MAX_PLACEMENT_SETTLE_TICKS, and drop the shift throttle so the
                // first tick after unfreeze may act immediately.
                if (isBodyFrozen(carriage)) {
                    PLACEMENT_TRACKER_FIRST_SEEN.put(subLevelId, firstSeenTick + 1L);
                    PLACEMENT_TRACKER_LAST_SHIFT.remove(subLevelId);
                    continue;
                }

                long ticksSinceFirstSeen = now - firstSeenTick;
                if (ticksSinceFirstSeen > MAX_PLACEMENT_SETTLE_TICKS) {
                    logPlacementStallState(trainId, carriage, train, provider, now, ticksSinceFirstSeen, "SAFETY-VALVE-FIRE");
                    provider.markPlacedSuccessfully();
                    PLACEMENT_TRACKER_FIRST_SEEN.remove(subLevelId);
                    forgetBurstFollowers(subLevelId);
                    continue;
                }
                if (ticksSinceFirstSeen > MAX_PLACEMENT_SETTLE_TICKS - PLACEMENT_STALL_APPROACH_TICKS
                    && (ticksSinceFirstSeen % 20) == 0) {
                    logPlacementStallState(trainId, carriage, train, provider, now, ticksSinceFirstSeen, "APPROACHING-VALVE");
                }

                SpawnCollisionCheck check = checkOneCarriage(trainId, carriage, train, now);
                if (check == null) continue;

                // A catch-up burst's follower is positionally OWNED by its leader while the
                // link is alive: its seam to that leader is exact by construction and is held
                // by lockstep propagation, so measuring it here can only act on an AABB that
                // has not formed yet. Observed in play (2026-08-31, anchors 153..156): one
                // tick after spawn the follower read its 0.4 seam as 1.00, "corrected" -0.5,
                // and collided with its leader on the very next tick — the pair sawing at
                // each other instead of settling. It still accrues clean ticks and settles
                // normally; the link drops the moment the leader is placed, after which it
                // self-corrects like any other carriage.
                boolean burstFollower = isBurstFollower(subLevelId);

                // Drive the collide→move-together→collide lock BEFORE the
                // shift decision so this tick's pushback observation can
                // suppress this tick's move-together (the carriage doesn't
                // get a "one last move-together" after the locking collision).
                boolean lockFiredThisTick = false;
                if (check.colliding() && !burstFollower) {
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
                double gap = (check.colliding() && !burstFollower)
                    ? 0.0
                    : gapToTrainFacingSibling(trainId, carriage, train);
                // Unreachable gap ⇒ re-place, don't nudge. See LARGE_GAP_REPLACE_BLOCKS: past a few
                // blocks the 0.5-per-4-ticks nudge cannot close the distance inside the settle
                // budget, so chasing it only burns the budget and hands the safety valve a group
                // still far out of line. One corrective step puts the seam straight on
                // TARGET_GAP_BLOCKS; the next tick reads clean and the normal 60-tick settle runs.
                if (!burstFollower && isUnreachableGap(check.colliding(), gap)) {
                    int confirmed = PLACEMENT_TRACKER_LARGE_GAP_TICKS.merge(subLevelId, 1, Integer::sum);
                    if (confirmed >= LARGE_GAP_CONFIRM_TICKS) {
                        double jump = placementTrackerReplaceDx(gap, provider.isSpawnedBackward());
                        applyPlacementShift(provider, subLevelId, jump, now);
                        provider.resetConsecutiveCleanTicks();
                        PLACEMENT_TRACKER_LAST_SHIFT.put(subLevelId, now);
                        PLACEMENT_TRACKER_LARGE_GAP_TICKS.remove(subLevelId);
                        LOGGER.info("[DungeonTrain] Placement tracker: pIdx={} unreachable gap={} blocks after {} confirming ticks — re-placed {} X onto the {}-block target seam",
                            provider.getPIdx(), String.format("%.2f", gap), LARGE_GAP_CONFIRM_TICKS,
                            String.format("%+.2f", jump), TARGET_GAP_BLOCKS);
                    }
                    continue;
                }
                PLACEMENT_TRACKER_LARGE_GAP_TICKS.remove(subLevelId);

                double dx = burstFollower ? 0.0 : placementTrackerShiftDx(
                    check.colliding(),
                    check.selfPIdx(),
                    check.collidingPIdx(),
                    gap,
                    provider.isSpawnedBackward(),
                    provider.isMoveTogetherLocked());

                if (dx != 0.0) {
                    // Throttle: don't stack a second shift before the worldAABB
                    // reflects the previous one (see SHIFT_SETTLE_TICKS). Reading
                    // a stale gap and re-shifting is what made carriages
                    // overshoot the dead-band and bounce 0.0↔1.0 forever. Leave
                    // the clean-tick counter untouched while waiting.
                    long lastShift = PLACEMENT_TRACKER_LAST_SHIFT.getOrDefault(subLevelId, Long.MIN_VALUE);
                    if (lastShift != Long.MIN_VALUE && now - lastShift < SHIFT_SETTLE_TICKS) {
                        continue;
                    }
                    PLACEMENT_TRACKER_LAST_SHIFT.put(subLevelId, now);
                    applyPlacementShift(provider, subLevelId, dx, now);
                    provider.resetConsecutiveCleanTicks();
                    if (check.colliding()) {
                        LOGGER.info("[DungeonTrain] Placement tracker: pIdx={} colliding (overlaps pIdx={}) — shifted {} X, timer reset",
                            provider.getPIdx(), check.collidingPIdx(),
                            String.format("%+.1f", dx));
                        if (lockFiredThisTick) {
                            LOGGER.info("[DungeonTrain] Placement tracker: pIdx={} move-together LOCKED (collide→move-together→collide cycle observed)",
                                provider.getPIdx());
                        }
                    } else if (Double.isFinite(gap) && gap < MIN_GAP_BLOCKS) {
                        // Too-close pushback: separating from the train-facing
                        // sibling to restore the floor. Like a collision
                        // pushback, NOT a move-together — do not mark the
                        // move-together cycle here.
                        LOGGER.info("[DungeonTrain] Placement tracker: pIdx={} too-close (gap={} blocks < {}) — shifted {} X, timer reset",
                            provider.getPIdx(),
                            String.format("%.2f", gap),
                            MIN_GAP_BLOCKS,
                            String.format("%+.1f", dx));
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
        PLACEMENT_TRACKER_LAST_SHIFT.keySet().retainAll(liveSubLevelIds);
        PLACEMENT_TRACKER_LARGE_GAP_TICKS.keySet().retainAll(liveSubLevelIds);
        BURST_FOLLOWERS.keySet().retainAll(liveSubLevelIds);
        BURST_FOLLOWER_OF.keySet().retainAll(liveSubLevelIds);
        // A follower whose leader is gone is nobody's passenger any more.
        BURST_FOLLOWER_OF.values().removeIf(leaderId -> !liveSubLevelIds.contains(leaderId));
    }

    /**
     * Apply one placement-tracker shift to {@code provider}, then propagate the
     * IDENTICAL {@code dx} to every group a catch-up burst chained off it, so a
     * burst settles as a rigid unit (see {@link #BURST_FOLLOWERS} for why).
     *
     * <p>Each follower also has its clean-tick counter reset and its shift
     * throttle stamped: the pair must settle together, and the follower must
     * not immediately re-shift off a gap reading that predates the move — the
     * same stale-read stacking {@link #SHIFT_SETTLE_TICKS} exists to
     * prevent.</p>
     */
    static void applyPlacementShift(
        TrainTransformProvider provider, UUID subLevelId, double dx, long now) {
        provider.shiftSpawnPosition(dx, 0.0, 0.0);
        // Propagate only a shift that actually landed. shiftSpawnPosition is a
        // no-op until Sable captures spawnWorldPos, so moving the followers
        // while the leader stood still would open the very seam this linkage
        // exists to hold.
        if (provider.hasCapturedSpawnPosition()) {
            shiftBurstFollowers(subLevelId, dx, now, 0);
        }
    }

    /**
     * Link {@code follower} to the group it was chained off by a catch-up
     * burst, so a placement-tracker shift on that group moves this one by the
     * same {@code dx} (see {@link #BURST_FOLLOWERS}).
     */
    static void linkBurstFollower(UUID leaderSubLevelId, UUID followerSubLevelId, TrainTransformProvider follower) {
        BURST_FOLLOWERS
            .computeIfAbsent(leaderSubLevelId, k -> new ArrayList<>())
            .add(new BurstFollower(followerSubLevelId, follower));
        BURST_FOLLOWER_OF.put(followerSubLevelId, leaderSubLevelId);
    }

    /**
     * Whether this sub-level is a catch-up burst's follower with a live link —
     * i.e. its position is owned by its leader and it must not steer itself.
     */
    static boolean isBurstFollower(UUID subLevelId) {
        return BURST_FOLLOWER_OF.containsKey(subLevelId);
    }

    /**
     * Drop {@code leaderSubLevelId}'s burst links. Called once that group is
     * placed (or force-finalised): it will never shift again, so there is
     * nothing left to propagate and the providers must not be held.
     */
    static void forgetBurstFollowers(UUID leaderSubLevelId) {
        List<BurstFollower> released = BURST_FOLLOWERS.remove(leaderSubLevelId);
        if (released == null) return;
        // Released followers become ordinary carriages again: the tracker may
        // steer them from here on, which is what should happen once their
        // leader has stopped moving.
        for (BurstFollower follower : released) {
            BURST_FOLLOWER_OF.remove(follower.subLevelId(), leaderSubLevelId);
        }
    }

    /**
     * Walk the burst chain from {@code leaderSubLevelId}, applying {@code dx}
     * to each linked follower. Depth-capped at {@link #CATCH_UP_BURST_GROUPS}
     * — the longest chain a burst can build — so a corrupted link can never
     * recurse without bound.
     */
    static void shiftBurstFollowers(UUID leaderSubLevelId, double dx, long now, int depth) {
        if (depth >= CATCH_UP_BURST_GROUPS) return;
        List<BurstFollower> followers = BURST_FOLLOWERS.get(leaderSubLevelId);
        if (followers == null || followers.isEmpty()) return;
        for (BurstFollower follower : followers) {
            follower.provider().shiftOrDeferSpawnShiftX(dx);
            follower.provider().resetConsecutiveCleanTicks();
            PLACEMENT_TRACKER_LAST_SHIFT.put(follower.subLevelId(), now);
            LOGGER.info("[DungeonTrain] Placement tracker: pIdx={} moved {} X in sync with its burst leader (intra-burst seam preserved)",
                follower.provider().getPIdx(), String.format("%+.2f", dx));
            shiftBurstFollowers(follower.subLevelId(), dx, now, depth + 1);
        }
    }

    /**
     * True while this carriage's body is DT-frozen by the #646 soft-freeze — i.e. it is no longer
     * being teleported each tick, so its {@code worldAABB()} is frozen too and nothing the placement
     * tracker does to {@code spawnWorldPos} can be observed. Non-Sable ships (tests, other backends)
     * are never frozen.
     */
    private static boolean isBodyFrozen(Trains.Carriage carriage) {
        return carriage.ship() instanceof SableManagedShip sable
            && PhysicsFreeze.isFrozen(sable.subLevel());
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
        firePendingRelayEntitySpawns(level, provider); // leased builds' own entities, same settle point
        PendingContentsEntitySpawn[] pending = provider.takePendingContentsEntitySpawns();
        if (pending == null) return;
        int fired = 0;
        for (PendingContentsEntitySpawn p : pending) {
            if (p == null) continue;
            try {
                CarriagePlacer.applyContentsEntitiesAt(level,
                    p.shipyardOrigin(), p.variant(), p.dims(), p.config(), p.carriageIndex(), p.groupAnchorWorldX());
                fired++;
            } catch (Throwable t) {
                LOGGER.warn("[DungeonTrain] Deferred contents-entity spawn failed for pIdx={} origin={}: {}",
                    p.carriageIndex(), p.shipyardOrigin(), t.toString());
            }
        }
        LOGGER.info("[DungeonTrain] Placement tracker: fired deferred contents-entity spawn for group anchorPIdx={} ({} of {} slots had pending entities)",
            provider.getPIdx(), fired, pending.length);

        // Fires once per group at the same settle point (the pending array has
        // already been atomically taken, so this can't double-spawn). Gated by
        // a 1-in-N config roll inside the spawner.
        PlayerMobGroupSpawner.maybeSpawnForGroup(level, provider, pending);
    }

    /**
     * Spawn the entities of every LEASED carriage in this group — the armor stands, item frames and mobs
     * the world that authored the build had standing in it, captured into the relay blob and put back
     * here. Fires from the same settle point, and for the same reason, as the contents spawns above.
     *
     * <p>A leased carriage that carried no entities has no pending record at all, so the common case
     * costs nothing. Failures are logged and skipped: the blocks are already down, and a build missing an
     * item frame is a far better outcome than a group that never finishes spawning.</p>
     */
    private static void firePendingRelayEntitySpawns(ServerLevel level, TrainTransformProvider provider) {
        PendingRelayEntitySpawn[] pending = provider.takePendingRelayEntitySpawns();
        if (pending == null) return;
        int spawned = 0;
        int slots = 0;
        for (PendingRelayEntitySpawn p : pending) {
            if (p == null) continue;
            slots++;
            try {
                spawned += CarriageEntitySnapshot.spawn(level, p.shipyardOrigin(), p.ents(), p.carriagePIdx());
            } catch (Throwable t) {
                LOGGER.warn("[DungeonTrain] Deferred relay-entity spawn failed for pIdx={} origin={}: {}",
                    p.carriagePIdx(), p.shipyardOrigin(), t.toString());
            }
        }
        if (slots > 0) {
            LOGGER.info("[DungeonTrain] Placement tracker: spawned {} entity(s) for {} leased carriage(s) in group anchorPIdx={}",
                spawned, slots, provider.getPIdx());
        }
    }

    /**
     * Pure decision helper for {@link #runPlacementCollisionTracker}. Package-private
     * for unit tests. Returns the X-axis shift to apply this tick:
     * <ul>
     *   <li>{@code colliding == true} → push AWAY from offender by a full
     *       {@code COLLISION_SHIFT_BLOCKS}. Offender at higher pIdx (in front of
     *       us) → {@code -COLLISION_SHIFT_BLOCKS}; behind us → {@code +…}. Never
     *       gated by {@code moveTogetherLocked}.</li>
     *   <li>gap inside the dead-band [{@link #MIN_GAP_BLOCKS}, {@link #MAX_GAP_BLOCKS}]
     *       (or no train-facing sibling) → {@code 0.0} (clean tick).</li>
     *   <li>gap {@code > MAX_GAP_BLOCKS} → pull TOWARD the train (shrink the
     *       gap), gated by {@code moveTogetherLocked}. Forward-spawn → negative,
     *       backward-spawn → positive.</li>
     *   <li>gap {@code < MIN_GAP_BLOCKS} → push AWAY (open the gap), never
     *       gated. Forward-spawn → positive, backward-spawn → negative.</li>
     * </ul>
     * Both out-of-band shifts are <b>proportional</b>: magnitude =
     * {@code min(COLLISION_SHIFT_BLOCKS, |gap − TARGET_GAP_BLOCKS|)}, aiming at
     * the band centre {@link #TARGET_GAP_BLOCKS} so the carriage lands on target
     * in one move instead of overshooting the narrow band and bouncing.
     *
     * <p>{@code gapToFacingSibling} is {@link Double#POSITIVE_INFINITY} when no
     * sibling is on the train-facing side (seed carriage) — clean by the
     * finite-check guard. {@code moveTogetherLocked} (owned by
     * {@link TrainTransformProvider}) flips true after the
     * collide → move-together → collide cycle, suppressing the toward-train pull
     * so the two systems can't fight; the separating (too-close) branch stays
     * active so a locked carriage is never stranded touching.</p>
     */
    /**
     * Whether {@code gap} is too wide for the tracker's nudge to ever close — see
     * {@link #LARGE_GAP_REPLACE_BLOCKS}. Never true while colliding: an overlap is resolved by the
     * pushback branch, and its gap is reported as zero anyway. Infinite gaps (no trustworthy
     * train-facing neighbour) are not separations and never qualify.
     */
    static boolean isUnreachableGap(boolean colliding, double gap) {
        return !colliding && Double.isFinite(gap) && gap > LARGE_GAP_REPLACE_BLOCKS;
    }

    /**
     * The single corrective shift that puts a group that has fallen out of the train back onto a
     * {@link #TARGET_GAP_BLOCKS} seam — the whole remaining distance, not a capped nudge. Sign
     * matches the move-together branch of {@link #placementTrackerShiftDx}: a backward-spawned group
     * closes toward +X, a forward-spawned one toward −X.
     */
    static double placementTrackerReplaceDx(double gap, boolean spawnedBackward) {
        double mag = gap - TARGET_GAP_BLOCKS;
        return spawnedBackward ? +mag : -mag;
    }

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
        if (!Double.isFinite(gapToFacingSibling)) {
            return 0.0; // no train-facing sibling (seed carriage) — nothing to settle against
        }
        // Clean while the real edge gap rests inside the dead-band.
        if (gapToFacingSibling >= MIN_GAP_BLOCKS && gapToFacingSibling <= MAX_GAP_BLOCKS) {
            return 0.0;
        }
        // Outside the band: shift PROPORTIONALLY toward the band centre
        // (TARGET_GAP_BLOCKS), capped at COLLISION_SHIFT_BLOCKS — never a fixed
        // step. The band is narrower than the 0.5 step, so a fixed shift would
        // fly across to the far side and bounce (the old 0.0<->1.0 oscillation);
        // a proportional shift lands on the target in one move, so every seam
        // converges to ~TARGET_GAP_BLOCKS — small and consistent.
        double mag = Math.min(COLLISION_SHIFT_BLOCKS,
            Math.abs(gapToFacingSibling - TARGET_GAP_BLOCKS));
        if (gapToFacingSibling > MAX_GAP_BLOCKS) {
            // Too far → pull TOWARD the train-facing sibling to shrink the gap.
            // Gated by the move-together lock so the two systems don't fight;
            // the separating branch below is always allowed.
            if (moveTogetherLocked) {
                return 0.0;
            }
            return spawnedBackward ? +mag : -mag;
        }
        // gap < MIN_GAP_BLOCKS → too close → push AWAY to open the gap. Never
        // gated by the lock: separating is always safe, and a locked carriage
        // must never be stranded touching.
        return spawnedBackward ? -mag : +mag;
    }

    /**
     * X-axis gap from this carriage's train-facing face to its IMMEDIATE train-facing neighbour's
     * AABB, in world blocks. Forward spawns measure the LOW-X face against anchor
     * {@code pIdx − groupSize}; backward spawns measure the HIGH-X face against
     * {@code pIdx + groupSize}. Y/Z must overlap (same lane).
     *
     * <p>Returns {@link Double#POSITIVE_INFINITY} — "nothing to settle against", read as clean by
     * the caller's finite check — whenever that one neighbour can't be trusted: it doesn't exist
     * (seed carriage), it has been culled ({@code !isResident}), its AABB is still degenerate
     * (spawned but not yet physics-ticked), or it doesn't qualify as train-facing.</p>
     *
     * <p><b>Adjacency is the whole point.</b> This used to take the minimum facing gap over EVERY
     * sibling in the visible train and the registry, skipping culled and zero-AABB ones. But a
     * skipped neighbour doesn't remove the measurement — it silently promotes a carriage two or
     * three groups away into the neighbour's place, and the "seam gap" reads as one or two whole
     * strides. Observed live: a correctly placed backward group (spawn log {@code gapBlocks=0.4000})
     * whose neighbour had just been culled measured a 90-block gap, and the tracker dragged it
     * 0.5 blocks/tick toward the train for the full MAX_PLACEMENT_SETTLE_TICKS budget before the
     * safety valve fired — which is exactly the wide hole a backward-riding player walks into. A
     * missing neighbour means there is no seam to settle, not a seam that is enormous.</p>
     *
     * <p>Collision detection deliberately does NOT scope this way ({@link #checkOneCarriage} still
     * tests every sibling): overlapping anything is real regardless of adjacency, whereas a seam
     * only exists between neighbours.</p>
     */
    private static double gapToTrainFacingSibling(
        UUID trainId,
        Trains.Carriage self,
        List<Trains.Carriage> train
    ) {
        AABBdc selfAabb = self.ship().worldAABB();
        if (isZeroAabb(selfAabb)) return Double.POSITIVE_INFINITY;
        TrainTransformProvider provider = self.provider();
        boolean spawnedBackward = provider.isSpawnedBackward();
        int groupSize = Math.max(1, provider.getGroupSize());
        int neighbourAnchor = provider.getPIdx() + (spawnedBackward ? groupSize : -groupSize);

        AABBdc neighbourAabb = null;
        for (Trains.Carriage other : train) {
            if (other.ship().id() == self.ship().id()) continue;
            if (other.provider().getPIdx() != neighbourAnchor) continue;
            AABBdc o = other.ship().worldAABB();
            if (!isZeroAabb(o)) neighbourAabb = o;
            break;
        }
        if (neighbourAabb == null) {
            ManagedShip registered = Trains.knownGroups(trainId).get(neighbourAnchor);
            // A culled neighbour's AABB is frozen at its cull-time pose — worse than no reading,
            // because the train has moved on since. Treat it as absent.
            if (registered != null && registered.id() != self.ship().id() && registered.isResident()) {
                AABBdc o = registered.worldAABB();
                if (!isZeroAabb(o)) neighbourAabb = o;
            }
        }
        if (neighbourAabb == null) return Double.POSITIVE_INFINITY;

        return facingGapBetween(
            selfAabb.minX(), selfAabb.maxX(),
            selfAabb.minY(), selfAabb.maxY(),
            selfAabb.minZ(), selfAabb.maxZ(),
            neighbourAabb.minX(), neighbourAabb.maxX(),
            neighbourAabb.minY(), neighbourAabb.maxY(),
            neighbourAabb.minZ(), neighbourAabb.maxZ(),
            spawnedBackward);
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
                // Registry-only (non-visible) sibling: its worldAABB is frozen
                // at the stale cull-time position. With the option-2 registry-
                // edge reference + the keep-frontier-resident hold, every real
                // neighbour is VISIBLE (checked above), so a registry-only box
                // here is a culled ghost. Letting the placement tracker shove a
                // settling carriage off it grows a permanent void (the −63/−60
                // 21-block seam). Skip it — visible siblings are authoritative.
                if (!ship.isResident()) continue;
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
        EDGE_UNRESOLVED_SINCE_FORWARD.clear();
        EDGE_UNRESOLVED_SINCE_BACKWARD.clear();
        EDGE_UNRESOLVED_WARNED_FORWARD.clear();
        EDGE_UNRESOLVED_WARNED_BACKWARD.clear();
        RELOAD_ISSUED_FORWARD.clear();
        RELOAD_ISSUED_BACKWARD.clear();
        // Force-load window tracking. The live Sable tickets themselves are
        // swept separately via Shipyard.releaseAllForceLoads() on the
        // train-wipe path (TrainAssembler.deleteExistingTrains), which has a
        // level handle; here we only drop our in-memory mirror.
        FORCELOADED_BY_TRAIN.clear();
        RESUME_GRACE_UNTIL_TICK.clear();
        RESUME_STARTED_TICK.clear();
        SPAWN_GEN_WAIT_FORWARD.clear();
        SPAWN_GEN_WAIT_BACKWARD.clear();
        BURST_FOLLOWERS.clear();
        BURST_FOLLOWER_OF.clear();
        lastSyncGenTick = Long.MIN_VALUE;
    }

    /**
     * Backward-seam-gap diagnostic probe ({@code [seamgap]}). For every loaded
     * train, walks carriages in ascending pIdx order and logs the world-X gap
     * between each adjacent pair — {@code distance = nextAabb.minX − thisAabb.maxX},
     * the identical metric {@link CarriageGroupGap#compute} feeds the gap-line
     * overlay — together with each side's force-load and placement-settle state.
     *
     * <p>Pivoting {@code distanceBlocks} by {@code thisPIdx} across a backward
     * ride is the recorded proof of whether per-seam gaps GROW with backward
     * distance (rising slope toward more-negative pIdx ⇒ H1 reference/anchor
     * mismatch) or merely scatter inside the settle dead-band (flat ⇒ H2). The
     * force-load / placed flags show whether a gapping seam is still settling
     * or has frozen. Gated by {@link #SEAMGAP_TRACE_ENABLED}; sampled every
     * {@link #SEAMGAP_SAMPLE_PERIOD_TICKS} ticks by {@link #onLevelTick}.</p>
     */
    private static void logBackwardSeamGaps(ServerLevel level) {
        long now = level.getGameTime();
        Map<UUID, List<Trains.Carriage>> trains = Trains.byTrainId(level);
        for (Map.Entry<UUID, List<Trains.Carriage>> entry : trains.entrySet()) {
            UUID trainId = entry.getKey();
            List<Trains.Carriage> train = entry.getValue();
            if (train.size() < 2) continue;
            List<Trains.Carriage> sorted = new ArrayList<>(train);
            sorted.sort(Comparator.comparingInt(c -> c.provider().getPIdx()));
            Set<UUID> forceLoaded = FORCELOADED_BY_TRAIN.getOrDefault(trainId, Set.of());
            for (int i = 0; i < sorted.size() - 1; i++) {
                Trains.Carriage thisGroup = sorted.get(i);
                Trains.Carriage nextGroup = sorted.get(i + 1);
                AABBdc thisAabb = thisGroup.ship().worldAABB();
                AABBdc nextAabb = nextGroup.ship().worldAABB();
                if (isZeroAabb(thisAabb) || isZeroAabb(nextAabb)) continue;
                double distance = nextAabb.minX() - thisAabb.maxX();
                LOGGER.info("[DungeonTrain][seamgap] gameTick={} trainId={} thisPIdx={} nextPIdx={} distanceBlocks={} thisMaxX={} nextMinX={} thisForceLoaded={} nextForceLoaded={} thisPlaced={} nextPlaced={}",
                    now, trainId,
                    thisGroup.provider().getPIdx(), nextGroup.provider().getPIdx(),
                    String.format("%.4f", distance),
                    String.format("%.4f", thisAabb.maxX()),
                    String.format("%.4f", nextAabb.minX()),
                    forceLoaded.contains(thisGroup.ship().subLevelId()),
                    forceLoaded.contains(nextGroup.ship().subLevelId()),
                    thisGroup.provider().isPlacedSuccessfully(),
                    nextGroup.provider().isPlacedSuccessfully());
            }
        }
    }

    /**
     * Groups ONE lane may spawn in a single server tick while that lane is
     * <b>catching up</b> — see {@link #catchUpBurstGroups}. The steady-state
     * cadence is unchanged at one group per lane per gate opening: the burst
     * engages only while the lane is {@link #CATCH_UP_DEFICIT_GROUPS} groups or
     * more short of the players' needed pIdx window, and stops on its own the
     * moment that deficit closes.
     *
     * <p><b>Why the lane can't simply spawn faster.</b> The per-lane gate
     * ({@link #isLanePlacementGateClear}) holds the next spawn until the
     * previous one has run {@link #CLEAN_TICKS_FOR_SUCCESS} collision-free
     * ticks, because the placement tracker needs a settled neighbour before the
     * next group can be landed inside the [{@link #MIN_GAP_BLOCKS},
     * {@link #MAX_GAP_BLOCKS}] seam band. That gate IS the inter-spawn delay,
     * and dropping it is what puts carriages too close together or too far
     * apart. A burst adds throughput without touching it: the extra groups are
     * placed relative to the group spawned alongside them, not to a settling
     * neighbour.</p>
     *
     * <p><b>Why &gt; 1 is safe.</b> A burst's follow-on groups are NOT placed
     * against a live pose. {@link TrainAssembler#spawnGroup} deliberately
     * leaves {@code spawnWorldPos} unseeded (Sable fills it from
     * {@code input.currentPosition()} on the first kinematic tick), so a
     * just-spawned ship's {@code shipToWorld} is not yet meaningful this tick.
     * {@link #planChainedSpawn} therefore chains the previous group's
     * <em>planned</em> world X by a whole {@code subLevelStride +
     * TARGET_GAP_BLOCKS} — the same rolling-reference trick
     * {@link #eagerFillForBootstrap} uses to drop an entire train in one
     * tick.</p>
     */
    static final int CATCH_UP_BURST_GROUPS = 2;

    /**
     * How many groups behind its needed pIdx window a lane must be before the
     * catch-up burst engages.
     *
     * <p>At 2, a lane bursts only when the group it is about to spawn would
     * still leave it a full group short — i.e. one group per settle window is
     * provably not keeping up with the players' window (fast {@code speed}
     * config, a Sable cull/reload cycle, a chunk-gen deferral, or a placement
     * that needed shift cycles). At 1 the burst would fire on every ordinary
     * extension, which is exactly the un-paced spawning the placement gate
     * exists to prevent.</p>
     */
    static final int CATCH_UP_DEFICIT_GROUPS = 2;

    /**
     * Groups one lane may spawn this tick, given how far that lane is behind.
     *
     * <p>{@code deficitPIdx} is the lane's shortfall in CARRIAGE indices —
     * {@code globalMaxNeededPIdx − trainMaxAnchor} forward,
     * {@code trainMinAnchor − globalMinNeededPIdx} backward. Returns 1 (the
     * steady-state cadence) unless that shortfall is
     * {@link #CATCH_UP_DEFICIT_GROUPS} whole groups or more, in which case the
     * lane may spawn {@link #CATCH_UP_BURST_GROUPS} in one tick.</p>
     *
     * <p>Non-positive shortfalls clamp to 1: the lane is already covering the
     * window, so the group it is spawning is the last one it needs. Pure and
     * JOML-free so the trigger boundary is unit-testable without a level.</p>
     *
     * @throws IllegalArgumentException if {@code groupSize} is not positive
     */
    static int catchUpBurstGroups(int deficitPIdx, int groupSize) {
        if (groupSize <= 0) {
            throw new IllegalArgumentException("groupSize must be > 0, got " + groupSize);
        }
        if (deficitPIdx <= 0) return 1;
        int deficitGroups = Math.ceilDiv(deficitPIdx, groupSize);
        return (deficitGroups >= CATCH_UP_DEFICIT_GROUPS) ? CATCH_UP_BURST_GROUPS : 1;
    }

    /**
     * Hard upper bound on how many GROUPS the appender will spawn in a
     * single server tick: {@link #CATCH_UP_BURST_GROUPS} per direction.
     * Forward and backward spawn lanes run independently (separate
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
     * was approximating, so the Sable-lag protection is preserved. The
     * catch-up burst is the one exception, and it stays inside that
     * protection by chaining planned placements rather than reading the
     * in-flight group's pose — see {@link #CATCH_UP_BURST_GROUPS}.</p>
     *
     * <p>The {@link Trains#knownAnchors} registry remains the
     * source of truth for "what anchors does this train own" — even with
     * the throttle, any duplicate the appender accidentally requests is
     * deduped against the registry (the burst re-checks it per group). The
     * throttle is the architectural fix; the registry is the safety net.</p>
     *
     * <p>Throughput cost: at groupSize=3, this caps carriages added per
     * tick at 12 (3 per group × 2 groups × 2 directions), and only while
     * both lanes are behind. The seed group from
     * {@link TrainAssembler#spawnTrain} plus the appender's first ~15
     * ticks fully populate a typical auto-rd window (~14 groups at
     * render distance 12) in &lt;1 second per side — imperceptible.</p>
     */
    private static final int MAX_SPAWNS_PER_TICK = 2 * CATCH_UP_BURST_GROUPS;

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

        // Backward-seam-gap diagnostic (opt-in, off by default). Periodic
        // per-seam world-X gap-vs-pIdx snapshot used to diagnose the growing
        // backward-gap regression. Self-gated on the sample cadence; no-op
        // (and no Trains.byTrainId scan) unless seamgap-trace is on.
        if (SEAMGAP_TRACE_ENABLED
            && Math.floorMod(level.getGameTime(), SEAMGAP_SAMPLE_PERIOD_TICKS) == 0) {
            logBackwardSeamGaps(level);
        }

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
        EDGE_UNRESOLVED_SINCE_FORWARD.keySet().retainAll(trainsTouchedThisTick);
        EDGE_UNRESOLVED_SINCE_BACKWARD.keySet().retainAll(trainsTouchedThisTick);
        EDGE_UNRESOLVED_WARNED_FORWARD.keySet().retainAll(trainsTouchedThisTick);
        EDGE_UNRESOLVED_WARNED_BACKWARD.keySet().retainAll(trainsTouchedThisTick);
        RELOAD_ISSUED_FORWARD.keySet().retainAll(trainsTouchedThisTick);
        RELOAD_ISSUED_BACKWARD.keySet().retainAll(trainsTouchedThisTick);
        SPAWN_GEN_WAIT_FORWARD.keySet().retainAll(trainsTouchedThisTick);
        SPAWN_GEN_WAIT_BACKWARD.keySet().retainAll(trainsTouchedThisTick);
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

            // The debug panel resolves the carriage independently, in the frame of the group the
            // player is actually standing in rather than the lead group's — see occupiedPIdx. It
            // therefore changes on its own schedule and needs its own "did it change" record.
            if (DebugAccessEvents.isPermitted(player)) {
                Integer occupied = occupiedPIdx(train, player, dims, groupSize);
                Integer lastDebug = LAST_SENT_DEBUG_PIDX.get(uuid);
                if (occupied != null && !occupied.equals(lastDebug)) {
                    DungeonTrainNet.sendTo(player, debugCarriageAt(occupied));
                    LAST_SENT_DEBUG_PIDX.put(uuid, occupied);
                }
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
        if (!MANUAL_MODE && nearPlayerPIdxs.isEmpty()) {
            // Player left the train's vicinity — normally drop any trailing force-loads
            // so Sable can cull the now-unneeded train. The train stays iterable (and thus
            // releasable) until released, because the force-load is the only thing keeping
            // it resident.
            //
            // EXCEPTION — singleplayer resume grace: a pause/resume transiently flings the
            // (frozen) rider off the moving train, so they read as "not near" for a few
            // ticks while still present. While ResumeWatchdog's grace is active, hold the
            // force-loads (including the whole-train resume-hold) instead of releasing,
            // renewing the window each tick the rider is still not near — capped from the
            // resume start so a genuine post-resume walk-away still releases (#547/#548). A
            // genuine walk-away (no pause) never sets a grace deadline, so it releases as
            // before.
            long nowTick = level.getGameTime();
            if (withinResumeGrace(RESUME_GRACE_UNTIL_TICK.get(trainId), nowTick)) {
                if (shouldRenewResumeGrace(RESUME_STARTED_TICK.get(trainId), nowTick, RESUME_HOLD_CAP_TICKS)) {
                    RESUME_GRACE_UNTIL_TICK.put(trainId, nowTick + RESUME_GRACE_RENEW_TICKS);
                }
                return false; // resume grace active — hold the window, do not release
            }
            RESUME_GRACE_UNTIL_TICK.remove(trainId); // expired (or never set) — normal cull
            RESUME_STARTED_TICK.remove(trainId);
            releaseTrainForceLoads(level, trainId, train);
            return false;
        }

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

        // Backward-anchor-divergence diagnostic ([anchor-div], opt-in, off by
        // default). Tracks whether the REGISTRY-min anchor (used to derive the
        // backward spawn anchor) falls below the VISIBLE tail pIdx (the spawn
        // reference) as the player rides backward — the precondition for the
        // H1 reference/anchor mismatch. A span that grows over the ride
        // confirms H1 is possible; span ≈ 0 throughout rules H1 out and points
        // at the settle dead-band (H2). registryCount − visibleCount = the
        // count of culled-but-registered ghosts feeding H3.
        if (SEAMGAP_TRACE_ENABLED
            && Math.floorMod(level.getGameTime(), SEAMGAP_SAMPLE_PERIOD_TICKS) == 0) {
            int visibleTailPIdx = tail.provider().getPIdx();
            Set<UUID> fl = FORCELOADED_BY_TRAIN.get(trainId);
            LOGGER.info("[DungeonTrain][anchor-div] gameTick={} trainId={} registryMin={} registryMax={} visibleTailPIdx={} visibleLeadPIdx={} registryCount={} visibleCount={} forceLoadedCount={} span={}",
                level.getGameTime(), trainId,
                trainMinAnchor, trainMaxAnchor,
                visibleTailPIdx, leadAnchorPIdx,
                knownAnchors.size(), train.size(),
                (fl == null) ? 0 : fl.size(),
                visibleTailPIdx - trainMinAnchor);
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

        // How far each lane is BEHIND the players' needed window, in carriage
        // indices — the input to {@link #catchUpBurstGroups}. Sentinel-guarded:
        // with no near player the needed pIdx is Integer.MIN/MAX_VALUE and the
        // subtraction would overflow. Manual mode reports no deficit at all, so
        // a J press keeps its "spawn one group in front" contract instead of
        // occasionally spawning two.
        int forwardDeficitPIdx = (MANUAL_MODE || globalMaxNeededPIdx == Integer.MIN_VALUE)
            ? 0 : (globalMaxNeededPIdx - trainMaxAnchor);
        int backwardDeficitPIdx = (MANUAL_MODE || globalMinNeededPIdx == Integer.MAX_VALUE)
            ? 0 : (trainMinAnchor - globalMinNeededPIdx);

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

        // ---- Option 2: registry-edge reference resolution -------------------
        // Place each new group against the REGISTRY-edge carriage's LIVE pose
        // (not the visible tail/lead), so planSpawnPlacement's subLevelDelta is
        // ±1 BY CONSTRUCTION (newAnchor = edgeAnchor ∓ groupSize, refAnchor =
        // edgeAnchor) — the gapless-stride void can't form. When the registry
        // edge has been culled to Sable holding, reload it and defer this tick;
        // when it's only transiently absent from findAll, fall back to the live
        // registry-wrapper pose; otherwise defer until it surfaces. A deferred
        // direction simply doesn't spawn this tick — never a hard stall.
        boolean backwardExtensionWanted = needsBackward;
        Trains.Carriage forwardRef = lead;
        Trains.Carriage backwardRef = tail;
        if (needsForward) {
            EdgeReference er = resolveEdgeReference(level, trainId, train, trainMaxAnchor, true, lead);
            if (er.reference() == null) needsForward = false;
            else forwardRef = er.reference();
        }
        if (needsBackward) {
            EdgeReference er = resolveEdgeReference(level, trainId, train, trainMinAnchor, false, tail);
            if (er.reference() == null) needsBackward = false;
            else backwardRef = er.reference();
        }
        // ---------------------------------------------------------------------

        // Maintain the trailing force-load window every tick — placed BEFORE
        // the no-spawn early return below so tickets are also released when
        // backward generation goes idle, not only refreshed while it's active.
        // A backward carriage spawned later this tick is force-loaded directly
        // at its spawn site (it isn't in `train` yet this tick). Pass the
        // PRE-defer backward intent so a one-tick reference defer never drops the
        // window (which is what would let Sable re-cull a just-reloaded edge).
        // playerNear: true iff a player is in this train's vicinity this tick.
        // In auto mode the no-near-players bail above already returned, but
        // manual mode skips that bail — so read the flag directly rather than
        // assuming near. globalMin/MaxNeededPIdx is the render-distance-bounded
        // carriage window around near players (sentinel MAX/MIN when none),
        // which drives the near-player resident window hold.
        maintainTrailingForceLoadWindow(
            level, trainId, train, backwardExtensionWanted,
            !nearPlayerPIdxs.isEmpty(), globalMinNeededPIdx, globalMaxNeededPIdx);

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
            Plan plan = planSpawnPlacement(forwardRef, forwardAnchor, groupSize, dims, train);
            NEXT_PLANNED_SPAWNS_FORWARD.put(trainId, new PlannedSpawn(
                trainId,
                forwardRef.ship().subLevelId(),
                plan.origin,
                plan.subLevelStride,
                plan.sizeY,
                plan.sizeZ,
                forwardAnchor));
        } else {
            NEXT_PLANNED_SPAWNS_FORWARD.remove(trainId);
        }
        if (needsBackward) {
            Plan plan = planSpawnPlacement(backwardRef, backwardAnchor, groupSize, dims, train);
            NEXT_PLANNED_SPAWNS_BACKWARD.put(trainId, new PlannedSpawn(
                trainId,
                backwardRef.ship().subLevelId(),
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
            Plan forwardPlan = planSpawnPlacement(forwardRef, forwardAnchor, groupSize, dims, train);
            ManagedShip newShip = spawnPlannedGroup(
                level, forwardPlan, forwardRef, forwardAnchor, groupSize, dims, velocity, trainId, train);
            // null ⇒ spawn deferred this tick while its footprint chunks generate
            // asynchronously (see ensureSpawnFootprintReady). Retry next tick; skip
            // all post-spawn bookkeeping and leave didForwardSpawn false.
            if (newShip != null) {
                recordSpawnedGroup(level, trainId, newShip, forwardAnchor, train, now, true);
                // Catch-up burst: while this lane is CATCH_UP_DEFICIT_GROUPS or more
                // groups short of the players' window, chain further groups on in the
                // same tick rather than paying a full settle window each. A no-op at
                // the steady-state one-group deficit.
                spawnCatchUpBurst(level, trainId, train, newShip, forwardPlan, forwardAnchor,
                    forwardDeficitPIdx, groupSize, dims, velocity, now, true);
                didForwardSpawn = true;
            }
        }

        if (needsBackward && isLanePlacementGateClear(LAST_SPAWNED_SHIP_BACKWARD, LAST_SPAWNED_TICK_BACKWARD, CULL_CLEARED_BACKWARD, trainId, train, now, false)) {
            Plan backwardPlan = planSpawnPlacement(backwardRef, backwardAnchor, groupSize, dims, train);
            ManagedShip newShip = spawnPlannedGroup(
                level, backwardPlan, backwardRef, backwardAnchor, groupSize, dims, velocity, trainId, train);
            // null ⇒ spawn deferred this tick for async footprint generation (see forward lane).
            if (newShip != null) {
                recordSpawnedGroup(level, trainId, newShip, backwardAnchor, train, now, false);
                // Catch-up burst — mirror of the forward lane (see there). This is the
                // end a stationary player watches pass them by when one group per
                // settle window can't keep up with the train's speed.
                spawnCatchUpBurst(level, trainId, train, newShip, backwardPlan, backwardAnchor,
                    backwardDeficitPIdx, groupSize, dims, velocity, now, false);
                didBackwardSpawn = true;
            }
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
     * <p>Cull-clear is bounded by {@code cullClearedFlags}, which maps a train to the game tick its
     * latch was stamped: at most ONE cull-clear per natural placement success. Once the latch fires,
     * the gate stays closed in this direction even after we remove the pending sub-level — extension
     * halts until a future spawn's {@code placedSuccessfully} flips through the normal tracker path,
     * at which point we clear the latch (the train has caught up to the player and Sable's plot
     * covers the train's end). This prevents the runaway cull→clear→spawn→cull cascade where each
     * cull-cleared anchor advances the registry frontier without filling in, producing a long chain
     * of registered-but-invisible ghost carriages.</p>
     *
     * <p>...but a latch whose only exit is "a later spawn succeeds" deadlocks the lane it shuts,
     * because no later spawn can happen. So the latch also expires after
     * {@link #CULL_LATCH_EXPIRY_TICKS} and lets one attempt through, re-stamping itself each time —
     * bounding the cascade by rate instead of by a one-shot that never re-arms.</p>
     */
    private static boolean isLanePlacementGateClear(
        Map<UUID, ManagedShip> lane,
        Map<UUID, Long> laneTickMap,
        Map<UUID, Long> cullClearedFlags,
        UUID trainId,
        List<Trains.Carriage> currentTrain,
        long now,
        boolean forward
    ) {
        ManagedShip pending = lane.get(trainId);
        if (pending == null) {
            Long latchedAtTick = cullClearedFlags.get(trainId);
            if (latchedAtTick == null) {
                return true;
            }
            if (!cullLatchExpired(latchedAtTick, now)) {
                return false;
            }
            // Latch expired (see CULL_LATCH_EXPIRY_TICKS) — the "next placement success clears it"
            // path can't fire while the lane it gates is shut, so let one more spawn through. The
            // latch is re-stamped rather than removed, so if that spawn is culled too the next
            // window is another full CULL_LATCH_EXPIRY_TICKS: progress stays bounded, and a
            // genuinely broken plot keeps announcing itself here once every 30 s.
            LOGGER.warn(
                "[DungeonTrain] Lane {} cull-clear latch expired after {} ticks — allowing one more spawn attempt (trainId={})",
                forward ? "forward" : "backward",
                now - latchedAtTick,
                trainId);
            cullClearedFlags.put(trainId, now);
            return true;
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
            if (cullClearedFlags.containsKey(trainId)) {
                return false;
            }
            long ticksSinceSpawn = (spawnTick == null) ? -1L : (now - spawnTick);
            LOGGER.warn(
                "[DungeonTrain] Lane {} pending sub-level {} culled by Sable before placement (ticksSinceSpawn={}) — clearing gate ONCE; further extension paused until next placement succeeds (trainId={})",
                forward ? "forward" : "backward",
                pendingSubLevelId,
                ticksSinceSpawn,
                trainId);
            cullClearedFlags.put(trainId, now);
            lane.remove(trainId);
            return true;
        }
        lane.remove(trainId);
        cullClearedFlags.remove(trainId);
        return true;
    }

    // ---- Option 2: registry-edge reference resolution -------------------------
    //
    // The backward-gap root cause was a reference/anchor mismatch:
    // planSpawnPlacement extrapolated idealX from the VISIBLE tail's pose but
    // newAnchor came from the REGISTRY min, so when the visible edge lagged the
    // registry edge subLevelDelta went to ±N and a gapless stride dropped the
    // accumulated seam gaps → a frozen void. Option 2 removes the mismatch at
    // the source: resolve the reference to the registry-EDGE carriage's live
    // pose, so subLevelDelta is ±1 by construction.

    /**
     * Whether a cull-clear latch stamped at {@code latchedAtTick} has aged out of its
     * {@link #CULL_LATCH_EXPIRY_TICKS} window and may let one spawn attempt through. Pure helper so
     * the expiry boundary is unit-testable without a level.
     */
    static boolean cullLatchExpired(long latchedAtTick, long now) {
        return now - latchedAtTick >= CULL_LATCH_EXPIRY_TICKS;
    }

    /** What to do with one extension edge this tick (output of {@link #decideEdgeAction}). */
    enum EdgeAction { SPAWN, RELOAD_DEFER, DEFER }

    /**
     * Resolved reference for an extension edge. {@code reference == null} means
     * "defer this direction this tick" (RELOAD_DEFER or DEFER); a non-null
     * reference is the {@link Trains.Carriage} to place the new group against.
     */
    private record EdgeReference(EdgeAction action, Trains.Carriage reference) {}

    /**
     * Pure decision core for registry-edge resolution. ORDER MATTERS: a held
     * edge's stale registry wrapper can still report a non-zero AABB, so
     * {@code held} is checked BEFORE trusting {@code registryResidentLiveAabb}.
     *
     * <ul>
     *   <li>{@code visibleLive} — the edge sub-level is in the visible train;
     *       use that fresh wrapper. → SPAWN</li>
     *   <li>{@code held} — culled to Sable holding; reload it, defer a tick.
     *       → RELOAD_DEFER</li>
     *   <li>{@code registryResidentLiveAabb} — a live (non-removed) registry
     *       wrapper with a non-zero AABB, i.e. a transient findAll dropout of a
     *       never-culled sub-level; use its live pose directly. → SPAWN</li>
     *   <li>otherwise — freshly spawned, not yet surfaced. → DEFER</li>
     * </ul>
     */
    static EdgeAction decideEdgeAction(boolean visibleLive, boolean held, boolean registryResidentLiveAabb) {
        if (visibleLive) return EdgeAction.SPAWN;
        if (held) return EdgeAction.RELOAD_DEFER;
        if (registryResidentLiveAabb) return EdgeAction.SPAWN;
        return EdgeAction.DEFER;
    }

    /**
     * The sub-level stride delta between a new anchor and its reference anchor.
     * Pure; package-private for the option-2 invariant test. With option 2 the
     * reference is always the registry edge and {@code newAnchor = edgeAnchor ∓
     * groupSize}, so this is {@code ∓1} on every spawn — the property the
     * {@code [bwd-place]} probe verifies in-game.
     */
    static int subLevelDeltaFor(int newAnchor, int refAnchor, int groupSize) {
        if (groupSize <= 0) throw new IllegalArgumentException("groupSize must be > 0, got " + groupSize);
        return (newAnchor - refAnchor) / groupSize;
    }

    /**
     * Resolve the placement reference for one extension edge to the
     * registry-edge carriage's live pose. Returns a spawnable
     * {@link EdgeReference} or a defer signal ({@code reference == null}).
     * Handles the RELOAD_DEFER side effect (reload-from-holding) and the
     * throttled unresolved-edge WARN. Server thread only.
     *
     * @param edgeAnchor      the registry extremum anchor for this direction
     *                        ({@code trainMaxAnchor} forward, {@code trainMinAnchor}
     *                        backward)
     * @param forward         true for the +X (lead) edge, false for the −X (tail) edge
     * @param visibleFallback the visible lead/tail, used only if the registry has
     *                        no entry for {@code edgeAnchor} (defensive)
     */
    private static EdgeReference resolveEdgeReference(
        ServerLevel level, UUID trainId, List<Trains.Carriage> train,
        int edgeAnchor, boolean forward, Trains.Carriage visibleFallback
    ) {
        ManagedShip registryShip = Trains.knownGroups(trainId).get(edgeAnchor);
        if (registryShip == null) {
            // Registry edge should always be registered; if not, fall back to
            // the visible edge (pre-option-2 behaviour) so we never hard-stall.
            clearEdgeUnresolved(trainId, forward);
            return new EdgeReference(EdgeAction.SPAWN, visibleFallback);
        }
        UUID uuid = registryShip.subLevelId();

        // Visible & live: prefer the FRESH visible wrapper (correct pose even
        // after a cull+reload, where the registry handle is stale).
        Trains.Carriage visible = null;
        for (Trains.Carriage c : train) {
            if (c.ship().subLevelId().equals(uuid)) { visible = c; break; }
        }
        boolean visibleLive = visible != null;

        Shipyard shipyard = Shipyards.of(level);
        boolean held = !visibleLive && shipyard.isHeld(uuid);
        boolean registryResidentLiveAabb = !visibleLive && !held
            && registryShip.isResident() && !isZeroAabb(registryShip.worldAABB());

        EdgeAction action = decideEdgeAction(visibleLive, held, registryResidentLiveAabb);
        switch (action) {
            case SPAWN -> {
                clearEdgeUnresolved(trainId, forward);
                if (visibleLive) return new EdgeReference(action, visible);
                // Registry-wrapper-resident path (transient findAll dropout):
                // build a Carriage from the live handle + its UUID-pinned provider.
                if (registryShip.getKinematicDriver() instanceof TrainTransformProvider provider) {
                    return new EdgeReference(action, new Trains.Carriage(registryShip, provider));
                }
                // No provider (shouldn't happen) — fall back to the visible edge.
                return new EdgeReference(EdgeAction.SPAWN, visibleFallback);
            }
            case RELOAD_DEFER -> {
                // Actively bring the culled edge back from Sable holding (a
                // force-load ticket CANNOT — Sable snatch-loads only at world
                // load). It surfaces in findAll within a few ticks; the sticky
                // trailing force-load window (engaged via backwardExtensionWanted)
                // then keeps it resident so it can't be re-culled before the
                // next group places one stride behind its live pose.
                //
                // Issue the reload only ONCE per held-edge episode: the edge stays
                // in RELOAD_DEFER for the whole surfacing window, but the call does
                // not itself load anything here (see RELOAD_ISSUED_* / claimReloadIssue)
                // — re-calling it every tick just re-triggers Sable's benign
                // "wasn't present in the holding chunk" ERROR. Recovery is the
                // force-load window + findAll, which run regardless.
                if (claimReloadIssue(trainId, forward, uuid)) {
                    boolean reloaded = shipyard.reloadFromHolding(uuid);
                    if (reloaded) {
                        LOGGER.debug("[DungeonTrain] Reloaded held registry-edge group anchor={} (subLevelId={}) for trainId={} — {} lane resumes once it surfaces",
                            edgeAnchor, uuid, trainId, forward ? "forward" : "backward");
                    }
                }
                warnEdgeUnresolvedIfStuck(trainId, forward, edgeAnchor, uuid, level.getGameTime(), "held→reload");
                return new EdgeReference(action, null);
            }
            default -> { // DEFER
                warnEdgeUnresolvedIfStuck(trainId, forward, edgeAnchor, uuid, level.getGameTime(), "absent(not-yet-surfaced)");
                return new EdgeReference(action, null);
            }
        }
    }

    /** Clear the unresolved-edge tracking + WARN latch for one direction. */
    private static void clearEdgeUnresolved(UUID trainId, boolean forward) {
        (forward ? EDGE_UNRESOLVED_SINCE_FORWARD : EDGE_UNRESOLVED_SINCE_BACKWARD).remove(trainId);
        (forward ? EDGE_UNRESOLVED_WARNED_FORWARD : EDGE_UNRESOLVED_WARNED_BACKWARD).remove(trainId);
        // Re-arm the reload-from-holding throttle: once this edge resolves, a later
        // held edge in the same direction (a different sub-level) should issue once.
        (forward ? RELOAD_ISSUED_FORWARD : RELOAD_ISSUED_BACKWARD).remove(trainId);
    }

    /**
     * Claim the single reload-from-holding issue for a held edge episode. Returns
     * {@code true} only the first call per {@code (trainId, forward, subLevelId)} —
     * subsequent ticks on the same held edge return {@code false} (the reload is a
     * no-op that would only re-spam Sable's benign snatch-miss ERROR). A new
     * {@code subLevelId} in the same direction re-arms it (a genuinely new episode),
     * as does {@link #clearEdgeUnresolved} once the edge resolves. See
     * {@code RELOAD_ISSUED_*}.
     */
    static boolean claimReloadIssue(UUID trainId, boolean forward, UUID subLevelId) {
        Map<UUID, UUID> issued = forward ? RELOAD_ISSUED_FORWARD : RELOAD_ISSUED_BACKWARD;
        return !subLevelId.equals(issued.put(trainId, subLevelId));
    }

    /**
     * Emit at most one WARN once a registry edge has stayed unresolved past
     * {@link #EDGE_UNRESOLVED_WARN_TICKS}. Diagnostic only — resolution
     * self-heals as the edge surfaces / finishes reloading, so this never
     * changes behaviour.
     */
    private static void warnEdgeUnresolvedIfStuck(
        UUID trainId, boolean forward, int edgeAnchor, UUID uuid, long now, String why
    ) {
        Map<UUID, Long> since = forward ? EDGE_UNRESOLVED_SINCE_FORWARD : EDGE_UNRESOLVED_SINCE_BACKWARD;
        Map<UUID, Boolean> warned = forward ? EDGE_UNRESOLVED_WARNED_FORWARD : EDGE_UNRESOLVED_WARNED_BACKWARD;
        long elapsed = now - since.computeIfAbsent(trainId, k -> now);
        if (elapsed >= EDGE_UNRESOLVED_WARN_TICKS && warned.putIfAbsent(trainId, Boolean.TRUE) == null) {
            LOGGER.warn("[DungeonTrain] Registry {} edge unresolved for {} ticks ({}) anchor={} subLevelId={} trainId={} — extension paused until it surfaces (no void, no delete)",
                forward ? "forward" : "backward", elapsed, why, edgeAnchor, uuid, trainId);
        }
    }

    // ---- Trailing-segment force-load window (backward-generation-stall fix) ----
    //
    // Sable culls any sub-level whose world chunks leave the player-centred
    // simulation bubble (PhysicsChunkTicketManager). A backward-riding player's
    // newest carriages drift out of that bubble and get culled before the
    // 60-tick placement settle, stalling backward generation. We hold the
    // active trailing frontier resident with Sable 2.0.2 force-load tickets
    // (routed through Shipyard.forceLoad), then release them once the segment
    // is no longer trailing so memory stays bounded.

    /**
     * Lightweight (pIdx, sub-level id) pair for the pure force-load target
     * selector. Package-private + static so {@link #backmostForceLoadTargets}
     * is unit-testable without a live {@link Trains.Carriage} or Sable.
     */
    record TrailingId(int pIdx, UUID subLevelId) {}

    /**
     * Pure core of the trailing-window policy: the sub-level IDs of the
     * {@code maxCarriages} carriages nearest the tail (lowest pIdx). Ties on
     * pIdx break by sub-level id so the result is deterministic.
     */
    static Set<UUID> backmostForceLoadTargets(List<TrailingId> carriages, int maxCarriages) {
        if (maxCarriages <= 0 || carriages.isEmpty()) return Set.of();
        List<TrailingId> sorted = new ArrayList<>(carriages);
        sorted.sort(Comparator.comparingInt(TrailingId::pIdx)
            .thenComparing(t -> t.subLevelId().toString()));
        Set<UUID> out = new HashSet<>();
        int n = Math.min(maxCarriages, sorted.size());
        for (int i = 0; i < n; i++) {
            out.add(sorted.get(i).subLevelId());
        }
        return out;
    }

    /**
     * Maintain the trailing force-load window for one train, once per tick.
     * Once backward generation has engaged for a train the backmost
     * {@link #TRAILING_FORCELOAD_GROUPS} groups stay force-loaded
     * <em>continuously</em> (sliding as new backward carriages spawn) so Sable
     * can't cull them mid-settle.
     *
     * <p>There is deliberately NO time-based idle release: mass-releasing the
     * tail when the player pauses lets Sable cull + reload those sub-levels, and
     * the post-reload position drift makes the next backward spawn overlap its
     * predecessor so it can never settle. The window shrinks only via the slide
     * ({@link #reconcileForceLoads} drops <em>settled</em> carriages that fall
     * out of the backmost-N), the walk-away bail
     * ({@link #releaseTrainForceLoads}), or a train wipe.</p>
     */
    /**
     * Decide whether one loaded group should be pinned resident by the
     * near-player window hold. True iff a player is near the train AND the
     * group's carriage range {@code [anchorPIdx, groupHighestPIdx]} overlaps the
     * render-distance-bounded near-player window {@code [nearMinPIdx, nearMaxPIdx]}
     * ({@code globalMinNeededPIdx}/{@code globalMaxNeededPIdx} from
     * {@link #updateTrain}). Pure + package-private for unit testing, mirroring
     * the {@link #shouldRetainOnWalkAway} / {@link #decideEdgeAction} convention.
     *
     * <p>This is a WINDOW test, not a cap: the held set is naturally bounded by
     * render distance and slides with the player, so it never dumps the
     * near-player set all at once (the mass-release churn a fixed group cap
     * produced once a long ride exceeded it).</p>
     *
     * <p>When no player is near, callers pass the sentinel window
     * {@code nearMinPIdx = Integer.MAX_VALUE}, {@code nearMaxPIdx = Integer.MIN_VALUE},
     * which fails the {@code playerNear} guard and the overlap test — no hold.</p>
     */
    static boolean shouldHoldGroupNearPlayer(
        boolean playerNear, int anchorPIdx, int groupHighestPIdx, int nearMinPIdx, int nearMaxPIdx
    ) {
        return playerNear
            && nearMinPIdx <= nearMaxPIdx
            && groupHighestPIdx >= nearMinPIdx
            && anchorPIdx <= nearMaxPIdx;
    }

    private static void maintainTrailingForceLoadWindow(
        ServerLevel level, UUID trainId, List<Trains.Carriage> train,
        boolean needsBackward, boolean playerNear, int nearMinPIdx, int nearMaxPIdx
    ) {
        // Sticky: stay engaged once we've started force-loading this train, so a
        // pause in backward travel never drops (then re-acquires) the window.
        boolean active = needsBackward || FORCELOADED_BY_TRAIN.containsKey(trainId);

        Map<UUID, Trains.Carriage> byId = new HashMap<>(train.size());
        List<TrailingId> ids = new ArrayList<>(train.size());
        for (Trains.Carriage c : train) {
            UUID id = c.ship().subLevelId();
            byId.put(id, c);
            ids.add(new TrailingId(c.provider().getPIdx(), id));
        }
        // During a singleplayer resume-recovery window keep the WHOLE train resident
        // (every visible carriage), not just the trailing-N. On a resume the rider is
        // transiently flung off, so without this the near/ahead carriages — which rely on
        // Sable proximity residency, not DT's window — cull and (if not reloadable)
        // regenerate. Holding the full set until the grace lapses (rider stably aboard)
        // closes that window; reconcile drains it back to the trailing-N the moment it
        // lapses (#547/#548).
        // Build the force-load target set. A singleplayer resume still pins the
        // WHOLE train transiently (#547/#548). Otherwise the target is the UNION
        // of the trailing-N window (backward-gen) and a render-distance-bounded
        // near-player WINDOW: every loaded group whose carriage range overlaps
        // [nearMinPIdx, nearMaxPIdx]. The window closes the steady-state riding
        // hole — mid/rear carriages that leave the player's sim bubble but sit
        // outside the trailing-N would otherwise cull → reload → catch-up-
        // teleport (the `[tripwire]` jitter) and network movement to clients
        // that culled them (Sable's "non-existent sub-level" error). Pinning
        // already-loaded groups loads no new chunks; it only stops Sable culling
        // them, so those drivers keep ticking (no gap, no catch-up). Because it
        // is a window (not a fixed cap) it slides with the player and never dumps
        // the near-player set all at once — the mass-release churn a group cap
        // caused once a long ride exceeded it.
        Set<UUID> target;
        if (isResumeHoldActive(trainId, level.getGameTime())) {
            target = new HashSet<>(byId.keySet());
        } else {
            target = new HashSet<>();
            if (active) {
                // The train list is one entry per group/sub-level, so target the
                // backmost-N GROUPS directly (not × groupSize).
                target.addAll(backmostForceLoadTargets(ids, TRAILING_FORCELOAD_GROUPS));
            }
            if (playerNear) {
                for (Trains.Carriage c : train) {
                    TrainTransformProvider p = c.provider();
                    if (shouldHoldGroupNearPlayer(
                            true, p.getPIdx(), p.getGroupHighestPIdx(), nearMinPIdx, nearMaxPIdx)) {
                        target.add(c.ship().subLevelId());
                    }
                }
            }
        }

        reconcileForceLoads(level, trainId, target, byId);
    }

    /**
     * Drive the live Sable tickets toward {@code target}: force-load every
     * target sub-level not yet ticketed, release every ticketed sub-level no
     * longer targeted. {@code byId} maps the train's currently-visible
     * sub-level IDs to their carriage so releases have a {@link ManagedShip}
     * handle.
     *
     * <p>A ticketed id missing from {@code byId} is a just-spawned carriage
     * Sable hasn't surfaced yet (force-loaded, so it will appear within a few
     * ticks) — we keep tracking it rather than dropping it mid-flight. Any true
     * straggler is cleaned by the bootstrap {@code releaseAllForceLoads()}
     * sweep.</p>
     */
    private static void reconcileForceLoads(
        ServerLevel level, UUID trainId, Set<UUID> target,
        Map<UUID, Trains.Carriage> byId
    ) {
        Set<UUID> current = FORCELOADED_BY_TRAIN.get(trainId);
        if (current == null) {
            if (target.isEmpty()) return;
            current = new HashSet<>();
            FORCELOADED_BY_TRAIN.put(trainId, current);
        }
        Shipyard shipyard = Shipyards.of(level);

        int released = 0;
        for (Iterator<UUID> it = current.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (target.contains(id)) continue;
            Trains.Carriage c = byId.get(id);
            if (c == null) continue; // not yet visible — keep tracking
            // Never cull a carriage mid-settle: keep it force-loaded until it
            // has settled (placedSuccessfully). Releasing + reloading an
            // unsettled carriage drifts its position, making its successor
            // overlap it forever (the placement-collision stall). Once settled
            // it becomes releasable here as the window slides.
            if (!c.provider().isPlacedSuccessfully()) continue;
            // Option-2 "keep frontier resident" policy: never release a group
            // until it has been serialized at least once. A cull before first
            // serialization yields a null-pointer holding entry that can't be
            // revived (snatchAndLoad needs the pointer), so it becomes an
            // unrecoverable ghost that silently dead-ends backward generation.
            // Holding until reloadable guarantees every cull is recoverable.
            if (!c.ship().hasSerializationPointer()) continue;
            shipyard.releaseForceLoad(c.ship());
            it.remove();
            released++;
        }
        int added = 0;
        for (UUID id : target) {
            if (current.contains(id)) continue;
            Trains.Carriage c = byId.get(id);
            if (c == null) continue;
            shipyard.forceLoad(c.ship());
            current.add(id);
            added++;
        }

        if (added > 0 || released > 0) {
            LOGGER.debug("[DungeonTrain] Force-load window trainId={} now holding {} trailing sub-level(s) (+{} −{}, target={})",
                trainId, current.size(), added, released, target.size());
        }
        if (current.isEmpty()) {
            FORCELOADED_BY_TRAIN.remove(trainId);
        }
    }

    /**
     * Force-load a carriage the instant it spawns backward, before any cull
     * pass can run — the per-tick window sees the previous tick's visible train
     * and wouldn't cover it until next tick. Records it in
     * {@link #FORCELOADED_BY_TRAIN} so the window keeps (or later releases) it
     * like any other trailing carriage.
     */
    private static void forceLoadSpawnedBackward(ServerLevel level, UUID trainId, ManagedShip newShip) {
        holdGroupResident(level, trainId, newShip);
    }

    /**
     * Force-load a freshly-created group and track it in
     * {@link #FORCELOADED_BY_TRAIN}. The "keep frontier resident" half of the
     * option-2 fix: a held group is not released by {@link #reconcileForceLoads}
     * until it has gained a serialization pointer
     * ({@link ManagedShip#hasSerializationPointer()}), so a later cull always
     * lands in <em>reloadable</em> holding rather than as an unrecoverable
     * null-pointer ghost that silently dead-ends train extension. Called at
     * every group birth — bootstrap eager-fill and both auto-spawn lanes.
     */
    private static void holdGroupResident(ServerLevel level, UUID trainId, ManagedShip ship) {
        Shipyards.of(level).forceLoad(ship);
        FORCELOADED_BY_TRAIN.computeIfAbsent(trainId, k -> new HashSet<>()).add(ship.subLevelId());
    }

    /** The registry handle ({@link ManagedShip}) for a sub-level UUID in this train, or null. */
    private static ManagedShip findRegistryShipByUuid(UUID trainId, UUID subLevelId) {
        for (ManagedShip ship : Trains.knownGroups(trainId).values()) {
            if (ship.subLevelId().equals(subLevelId)) return ship;
        }
        return null;
    }

    /**
     * Release one force-load ticket by sub-level UUID, preferring the live
     * visible wrapper but falling back to the {@link Trains#knownGroups} registry
     * handle. Sable keys force-load tickets by sub-level UUID, so the registry
     * handle releases the live ticket even when the visible wrapper is gone —
     * this is the leak fix for a ticket whose carriage hasn't (or no longer)
     * surfaced in the visible train. Returns true iff a handle was found.
     */
    private static boolean releaseForceLoadByUuid(
        Shipyard shipyard, UUID trainId, UUID subLevelId, Map<UUID, Trains.Carriage> byId
    ) {
        Trains.Carriage c = (byId == null) ? null : byId.get(subLevelId);
        ManagedShip ship = (c != null) ? c.ship() : findRegistryShipByUuid(trainId, subLevelId);
        if (ship == null) return false;
        shipyard.releaseForceLoad(ship);
        return true;
    }

    /**
     * Release one train's trailing force-loads when the player leaves the train's
     * vicinity, so Sable can cull the now-unneeded train normally — with ONE
     * carve-out: a group that hasn't serialized yet ({@link #shouldRetainOnWalkAway})
     * stays held, exactly as {@link #reconcileForceLoads} keeps it. Culling an
     * un-serialized group yields a null-pointer holding entry that
     * {@code snatchAndLoad} can't revive, so it would respawn FRESH (re-rolled,
     * player edits lost) instead of reloading intact.
     *
     * <p>This bail formerly released <em>everything</em> on the premise that "the
     * player is gone." That premise is false on a singleplayer pause/resume: the
     * rider is only transiently flung off the moving train (Sable carry hasn't
     * re-grabbed them), so they read as "not near" for a few ticks while still
     * present. Stripping the un-serialized frontier groups in that window culled
     * them unrecoverably and regenerated the whole train (early-session, when none
     * have autosaved yet, that is literally every carriage). Retained groups stay
     * tracked in {@link #FORCELOADED_BY_TRAIN} and release on a later tick once they
     * serialize (or via {@link #clearSettleTracker} on a train wipe).</p>
     *
     * <p>UUID-keyed (option 2): a tracked ticket whose carriage isn't in the
     * visible train (e.g. a just-spawned backward group force-loaded via
     * {@link #forceLoadSpawnedBackward}, or a reloaded edge that hasn't surfaced)
     * is released through the registry handle so no Sable ticket leaks.</p>
     */
    private static void releaseTrainForceLoads(ServerLevel level, UUID trainId, List<Trains.Carriage> train) {
        Set<UUID> current = FORCELOADED_BY_TRAIN.get(trainId);
        if (current == null || current.isEmpty()) return;
        Shipyard shipyard = Shipyards.of(level);
        Map<UUID, Trains.Carriage> byId = new HashMap<>(train.size());
        for (Trains.Carriage c : train) byId.put(c.ship().subLevelId(), c);
        int released = 0;
        int keptUnserialized = 0;
        for (Iterator<UUID> it = current.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            // Resolve the live handle (visible wrapper, else registry handle — the
            // same resolution releaseForceLoadByUuid uses) to read its serialization
            // state. Keep un-serialized groups held + tracked; never strip them here
            // (see method doc; pause/resume regen, #547/#548). A null handle (stale
            // ticket, no live ship) has nothing to keep resident.
            Trains.Carriage c = byId.get(id);
            ManagedShip ship = (c != null) ? c.ship() : findRegistryShipByUuid(trainId, id);
            if (ship != null && shouldRetainOnWalkAway(ship.hasSerializationPointer())) {
                keptUnserialized++;
                continue;
            }
            if (releaseForceLoadByUuid(shipyard, trainId, id, byId)) released++;
            it.remove();
        }
        if (current.isEmpty()) FORCELOADED_BY_TRAIN.remove(trainId);
        if (released > 0 || keptUnserialized > 0) {
            LOGGER.debug("[DungeonTrain] Force-load window trainId={} released {} trailing sub-level(s), kept {} un-serialized held (player left vicinity)",
                trainId, released, keptUnserialized);
        }
    }

    /**
     * Walk-away release recoverability guard (pure core): a resolvable carriage must
     * stay force-loaded even though the player has left the train's vicinity iff it
     * has not yet serialized to disk ({@code !hasSerializationPointer}). Releasing an
     * un-serialized group would let Sable cull it into a null-pointer holding entry
     * that {@code snatchAndLoad} can't revive — the carriage would then respawn fresh
     * (re-rolled, player edits lost) instead of reloading intact. Mirrors the guard
     * {@link #reconcileForceLoads} applies to the sliding window (option 2).
     *
     * <p>The {@code ship != null} stale-ticket check stays at the call site (a ticket
     * with no live ship has nothing to keep resident). This boolean core is the
     * serialization decision only — package-private + pure for unit testing alongside
     * {@link #decideEdgeAction} / {@link #subLevelDeltaFor}.</p>
     */
    static boolean shouldRetainOnWalkAway(boolean hasSerializationPointer) {
        return !hasSerializationPointer;
    }

    /**
     * Spawn-time hold policy (pure core): a freshly-spawned group of EITHER auto-spawn lane
     * (forward or backward) is held resident until it has serialized at least once. Culling an
     * un-serialized sub-level yields a null-pointer holding entry {@code snatchAndLoad} can't
     * revive (the "train vanishes on autosave" report — actually a per-tick simulation-distance
     * cull of the just-spawned forward edge, not the save itself), so a later cull must always land
     * in <em>reloadable</em> holding. Holding both lanes symmetrically removes the dependency on the
     * player's simulation distance; the hold self-drains via {@link #reconcileForceLoads} once a
     * save mints a serialization pointer, so memory stays bounded by autosave cadence.
     *
     * <p>Constant by design (both lanes hold). Exists as a named, unit-tested decision point so a
     * future change can't silently re-introduce a lane-asymmetric hold policy — the exact regression
     * that lost forward carriages. Package-private + pure, like {@link #shouldRetainOnWalkAway}.</p>
     */
    static boolean shouldHoldSpawnedGroup(boolean forward) {
        return true;
    }

    /**
     * Force-load EVERY carriage of {@code train} and track them in
     * {@link #FORCELOADED_BY_TRAIN}, so a singleplayer pause/resume can't cull any part
     * of the train while the rider is transiently flung off the deck (Sable carry hasn't
     * re-grabbed them). The trailing-N window ({@link #maintainTrailingForceLoadWindow})
     * only force-loads the tail; carriages near/ahead of the player rely on Sable
     * <em>proximity</em> residency, which evaporates the moment the rider reads as "not
     * near" — so on a resume they would cull and, if not reloadable, regenerate. Pinning
     * the whole train here removes that dependency on the (transiently-absent) rider.
     *
     * <p>Called by {@code ResumeWatchdog} the instant it detects a resume, alongside
     * {@link #grantResumeGrace}: the grace (renewed in {@link #updateTrain}'s bail)
     * suppresses the walk-away release while these sticky tickets keep the whole train
     * resident through the fling. Once the rider re-anchors,
     * {@link #maintainTrailingForceLoadWindow}/{@link #reconcileForceLoads} drain the
     * window back to the trailing-N groups (releasing the now proximity-resident
     * carriages; un-serialized ones stay per {@link #shouldRetainOnWalkAway}).</p>
     *
     * <p>Idempotent — only force-loads a carriage not already ticketed (so it never
     * double-tickets a trailing/frontier group already held). Server-thread only.
     * (#547/#548.)</p>
     */
    public static void holdWholeTrainForResume(ServerLevel level, UUID trainId, List<Trains.Carriage> train) {
        if (train == null || train.isEmpty()) return;
        Shipyard shipyard = Shipyards.of(level);
        Set<UUID> current = FORCELOADED_BY_TRAIN.computeIfAbsent(trainId, k -> new HashSet<>());
        int held = 0;
        for (Trains.Carriage c : train) {
            if (current.add(c.ship().subLevelId())) {
                shipyard.forceLoad(c.ship());
                held++;
            }
        }
        if (held > 0) {
            LOGGER.debug("[DungeonTrain] Resume hold: force-loaded {} previously-unticketed carriage(s) of trainId={} ({} total held) so the resume-fling can't cull the train",
                held, trainId, current.size());
        }
    }

    /**
     * Pin <em>every</em> loaded train resident and grant each a resume-grace window — the shared
     * core behind both {@code ResumeWatchdog} (a singleplayer pause/resume) and the save hold
     * ({@code MinecraftServerSaveMixin}). Resolves the train level from world data, then for
     * each loaded train grants {@code graceTicks} of grace ({@link #grantResumeGrace}) and
     * force-loads the whole train ({@link #holdWholeTrainForResume}), so a transient "rider not
     * near" — a resume fling, or an autosave hitch — can't cull and unrecoverably regenerate any
     * carriage. Both holds self-drain back to the trailing-N window once the rider is stably
     * aboard ({@link #reconcileForceLoads}; un-serialized groups stay per
     * {@link #shouldRetainOnWalkAway}).
     *
     * <p>No-op (returns {@code 0}) when this world has no train, the train level isn't loaded, or
     * no train is currently loaded. Server-thread only.</p>
     *
     * @return the number of loaded trains held this call.
     */
    public static int holdAllLoadedTrains(MinecraftServer server, int graceTicks) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
        if (!data.startsWithTrain()) return 0;
        StartingDimension startingDim = data.startingDimension();
        ServerLevel trainLevel = server.getLevel(startingDim.levelKey());
        if (trainLevel == null) return 0;
        Map<UUID, List<Trains.Carriage>> trains = Trains.byTrainId(trainLevel);
        if (trains.isEmpty()) return 0;
        long nowTick = trainLevel.getGameTime();
        for (Map.Entry<UUID, List<Trains.Carriage>> entry : trains.entrySet()) {
            grantResumeGrace(entry.getKey(), nowTick, graceTicks);
            holdWholeTrainForResume(trainLevel, entry.getKey(), entry.getValue());
        }
        return trains.size();
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
     *
     * <p>Option 2: a culled-but-HELD (recoverable) anchor is NEVER deleted —
     * deleting it then respawning at the same anchor is the historic
     * delete-then-respawn DUPLICATE race, and option-2 resolution would just
     * reload it from holding anyway. Only truly-gone anchors (not held, not
     * visible) are dropped. Any force-load ticket on a dropped anchor is
     * released through the registry handle BEFORE delete so the DT mirror and
     * the live Sable ticket tear down together (no leak).</p>
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
        Set<UUID> forceLoaded = FORCELOADED_BY_TRAIN.get(trainId);
        java.util.List<Integer> removedAnchors = new java.util.ArrayList<>();
        int deletedSableShips = 0;
        int skippedHeld = 0;
        int sharedCaptured = 0; // full shared-carriage captures taken this pass (capped)
        int sharedDeferred = 0; // shared carriages whose final capture was skipped past the cap
        for (int a : toRemove) {
            ManagedShip registryShip = Trains.knownGroups(trainId).get(a);
            UUID subId = (registryShip != null) ? registryShip.subLevelId() : null;
            // Recoverable (held / mid-reload) — keep it registered; option-2
            // resolution reloads it on demand. Deleting + respawning here is the
            // duplicate-on-top race.
            if (subId != null && shipyard.isHeld(subId)) {
                skippedHeld++;
                continue;
            }
            ManagedShip ship = Trains.unregisterGroup(trainId, a);
            if (ship != null) {
                UUID shipId = ship.subLevelId();
                // Hand any shared carriages in this sub-level back to the relay (final flush + lease
                // return) and drop them from the registry BEFORE the plot is destroyed. The capture is
                // synchronous on the server thread (reading the still-live plot); only the return POST is
                // async. This plugs the SharedCarriageRegistry leak (removeSubLevel finally has a caller)
                // and frees the lease promptly instead of at the ~1h TTL.
                if (SharedCarriageRegistry.hasSubLevel(shipId)) {
                    for (SharedCarriageRegistry.Instance inst : SharedCarriageRegistry.bySubLevel(shipId)) {
                        boolean allowCapture = sharedCaptured < MAX_SHARED_CAPTURES_PER_CULL;
                        if (games.brennan.dungeontrain.event.SharedCarriageEvents.finalFlushAndReturn(inst, allowCapture)) {
                            sharedCaptured++;
                        } else if (!allowCapture && inst.hasPending()) {
                            sharedDeferred++;
                        }
                    }
                    SharedCarriageRegistry.removeSubLevel(shipId);
                }
                // Drop any despawn snapshot this group was holding. The group is about to stop
                // existing, so its swept mobs have nowhere to come back to — leaving the file would
                // strand it on disk until the next train wipe.
                ContentsSnapshotStore.delete(level, shipId);
                // Tear down any force-load ticket on this anchor (mirror + Sable
                // ticket together) before deleting it.
                if (forceLoaded != null && forceLoaded.remove(shipId)) {
                    shipyard.releaseForceLoad(ship);
                }
                shipyard.delete(ship);
                deletedSableShips++;
                removedAnchors.add(a);
            }
        }
        if (sharedDeferred > 0) {
            LOGGER.info("[DungeonTrain] cull: {} shared carriage(s) bare-returned past the per-pass capture cap "
                + "(last un-flushed edits left to streamed deltas + TTL); trainId={}", sharedDeferred, trainId);
        }
        if (forceLoaded != null && forceLoaded.isEmpty()) FORCELOADED_BY_TRAIN.remove(trainId);
        if (removedAnchors.isEmpty()) {
            if (skippedHeld > 0) {
                LOGGER.debug("[DungeonTrain] cleanupGhostAnchors: {} candidate anchor(s) past visible {}={} all held/recoverable — none deleted (trainId={})",
                    skippedHeld, forward ? "max" : "min", forward ? visibleMax : visibleMin, trainId);
            }
            return false;
        }
        LOGGER.info(
            "[DungeonTrain] Cleaned up {} ghost anchor(s) past visible {}={} for trainId={} — anchors={}, sableShipsDeleted={}, heldSkipped={}",
            removedAnchors.size(),
            forward ? "max" : "min",
            forward ? visibleMax : visibleMin,
            trainId,
            removedAnchors,
            deletedSableShips,
            skippedHeld);
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
     * One-shot eager fill at world-load time, called from
     * {@link games.brennan.dungeontrain.event.TrainBootstrapEvents#onServerStarted}
     * immediately after the seed group is spawned. Extends the seed train to
     * the server's configured view-distance window so the player's first
     * rendered frame already shows a fully-assembled train — instead of
     * spending the post-login ~8s in a synchronous spawn burst (the "stuck
     * at 100%" freeze that motivated this refactor) or growing it gradually
     * over ~30+ seconds via {@link #updateTrain}.
     *
     * <p>Caller passes the seed {@link ManagedShip} returned by
     * {@link TrainAssembler#spawnTrain} directly — at this point Sable's
     * {@code Shipyards.findAll()} is still empty for 1–2 ticks (lazy plot
     * bind), so {@link Trains#byTrainId} can't find the freshly-spawned
     * seed. The seed reference and {@link Trains#knownAnchors} /
     * {@link Trains#knownGroups} (synchronous registry populated inside
     * {@link TrainAssembler#spawnGroup}) are the authoritative sources we
     * use instead.</p>
     *
     * <p>Bypasses {@link #isLanePlacementGateClear} (the 60-tick
     * placement-success gate). Safe at bootstrap because:
     * <ul>
     *   <li>{@link Trains#knownAnchors} guarantees no duplicate spawns at
     *       the same anchor.</li>
     *   <li>New origins are computed via deterministic stride math from a
     *       fixed reference carriage's stored {@code getShipyardOrigin} —
     *       not via {@code shipToWorld}/AABB inspection — so Sable's lazy
     *       plot-load lag doesn't corrupt placement.</li>
     *   <li>At bootstrap the train hasn't ticked yet (game time hasn't
     *       advanced since {@code preSeedSpawnTick}), so the registry-frame
     *       stride math is exact w.r.t. the world.</li>
     * </ul></p>
     *
     * <p>Skips {@link #markCollidingNeighbours} and {@link #adjustForCollisions}
     * for the same reason — both consult live world AABBs which are stale
     * for groups whose sub-levels haven't been bound by Sable yet. The
     * stride math produces non-overlapping placements by construction.</p>
     *
     * <p>The actual joining player's render distance may exceed the
     * server-configured value used here; the per-tick appender path via
     * {@link #onLevelTick} extends the train further if needed once the
     * player connects.</p>
     */
    public static void eagerFillForBootstrap(ServerLevel level, ManagedShip seedShip) {
        if (seedShip == null) {
            LOGGER.debug("[DungeonTrain] Bootstrap eager fill skipped — null seedShip");
            return;
        }
        try {
            eagerFillTrainAtBootstrap(level, seedShip);
        } finally {
            // Always clear so a thrown exception during the loop doesn't
            // leave the loading-screen indicator stuck on the player's
            // client when they retry. The fill itself is wrapped in
            // try/catch by the caller (TrainBootstrapEvents.onServerStarted),
            // so any throw is already logged there.
            BootstrapProgress.clear();
        }
    }

    /**
     * Bootstrap-context eager-fill for a single train. Called once from
     * {@link #eagerFillForBootstrap} with the seed {@link ManagedShip}
     * directly (Sable hasn't bound it into {@code Shipyards.findAll()} yet,
     * so {@code Trains.byTrainId} would return empty here).
     *
     * <p>Differs from a player-driven eager fill in three places:</p>
     * <ul>
     *   <li>Target carriage count comes from
     *       {@link #bootstrapTargetFromServerViewDistance} (the server's
     *       {@code view-distance} property), not the joining player's
     *       render distance — there is no joining player yet.</li>
     *   <li>{@code playerPIdx} is hard-coded to {@code 0}. The cached
     *       world-spawn placement
     *       ({@link games.brennan.dungeontrain.event.PlayerJoinEvents#computeAndCacheBootstrapPlacement})
     *       puts the player near {@code X = 0}, which sits at or near the
     *       seed group's anchor pIdx of 0. If the joining player ends up
     *       slightly offset, the per-tick appender retargets on first
     *       tick.</li>
     *   <li>Mark-placed iterates
     *       {@link Trains#knownGroups}{@code .values()} (synchronous
     *       registry) instead of {@link Trains#findById} (Sable-binding
     *       dependent).</li>
     * </ul>
     */
    private static void eagerFillTrainAtBootstrap(
        ServerLevel level, ManagedShip seedShip
    ) {
        if (!(seedShip.getKinematicDriver() instanceof TrainTransformProvider refProvider)) {
            LOGGER.warn("[DungeonTrain] Bootstrap eager fill: seedShip has no TrainTransformProvider — skipping");
            return;
        }
        if (refProvider.isAppenderDisabled()) return;

        UUID trainId = refProvider.getTrainId();
        CarriageDims dims = refProvider.dims();
        Vector3dc realVelocity = new Vector3d(refProvider.getTargetVelocity());
        int groupSize = refProvider.getGroupSize();
        int length = dims.length();

        int configCount = DungeonTrainConfig.getNumCarriages();
        int rawTargetCount = (configCount > 0)
            ? configCount
            : bootstrapTargetFromServerViewDistance(level, length);

        // Cap to Sable's sub-level tracking range. Sable culls any sub-level
        // farther than {@code SUB_LEVEL_TRACKING_RANGE} blocks (default 320)
        // from every player into a serialised {@code HoldingSubLevel} the
        // moment the player isn't close enough — see
        // {@code SubLevelTrackingSystem.shouldLoad}. Eager-spawning past that
        // range is wasted work AND every reload cycle is where the
        // post-cull position-drift bug shows up. By capping at the tracking
        // range, the eager fill places only carriages that Sable will keep
        // resident around the player, eliminating both costs.
        //
        // Cap is (range * 2) / length because the range is radial: each side
        // of the player gets up to {@code range/length} carriages.
        double trackingRange = SableConfig.SUB_LEVEL_TRACKING_RANGE.getAsDouble();
        int trackingCap = Math.max(
            DungeonTrainConfig.MIN_CARRIAGES_AUTO_FLOOR,
            (int) ((trackingRange * 2.0) / Math.max(1, length)));
        int targetCount = Math.min(rawTargetCount, trackingCap);
        if (targetCount < rawTargetCount) {
            LOGGER.info("[DungeonTrain] Bootstrap eager fill: capped target {} → {} carriages by SableConfig.SUB_LEVEL_TRACKING_RANGE={} blocks (length={})",
                rawTargetCount, targetCount, trackingRange, length);
        }

        // At bootstrap there is no joining player to read a position from.
        // The cached world-spawn placement puts the player at X ≈ 0, which
        // is the seed group's anchor pIdx. Use 0 directly; the per-tick
        // appender retargets on the first player tick if they end up
        // offset.
        int playerPIdx = 0;

        int halfPadLen = CarriagePlacer.halfPadLen(dims);
        BlockPos refShipyardOrigin = refProvider.getShipyardOrigin();

        int halfBack = (targetCount - 1) / 2;
        int halfFront = targetCount - halfBack - 1;
        int neededMin = playerPIdx - halfBack;
        int neededMax = playerPIdx + halfFront;

        // Initialise the loading-screen progress indicator. The displayed
        // total is the group-aligned carriage count we'll actually place
        // (seed + ceil(halfFront/groupSize) forward groups + ceil(halfBack/
        // groupSize) backward groups, each × groupSize) — matches what the
        // loop produces so the indicator ends at "N / N" rather than
        // overshooting (target carriages 35 + groupSize 3 → 39 actual).
        int forwardGroupsToFill = Math.max(0, Math.ceilDiv(Math.max(0, neededMax), groupSize));
        int backwardGroupsToFill = Math.max(0, Math.ceilDiv(Math.max(0, -neededMin), groupSize));
        int alignedTotalCarriages = (1 + forwardGroupsToFill + backwardGroupsToFill) * groupSize;
        BootstrapProgress.start("gui.dungeontrain.loading.phase.assembling", alignedTotalCarriages, groupSize);

        int startMin = Integer.MAX_VALUE;
        int startMax = Integer.MIN_VALUE;
        for (int a : Trains.knownAnchors(trainId)) {
            if (a < startMin) startMin = a;
            if (a > startMax) startMax = a;
        }
        if (startMin == Integer.MAX_VALUE) {
            LOGGER.warn("[DungeonTrain] Bootstrap eager fill: trainId={} has no registered anchors — skipping", trainId);
            return;
        }
        int trainMin = startMin;
        int trainMax = startMax;

        // Reference world origin (seed group's shipyard origin → world space).
        // Used for placeY/placeZ — every group in the train sits at the
        // same Y/Z. NOT used as the rolling ref X — see below.
        int subLevelStride = (groupSize > 1) ? (groupSize * length + 2 * halfPadLen) : length;
        Vector3d refWorldOrigin = new Vector3d(
            refShipyardOrigin.getX(), refShipyardOrigin.getY(), refShipyardOrigin.getZ());
        seedShip.shipToWorld(refWorldOrigin);
        int placeY = (int) Math.round(refWorldOrigin.y);
        int placeZ = (int) Math.round(refWorldOrigin.z);

        // Rolling forward/backward reference world-X. CRITICAL: must start
        // from the CURRENT forward-most / backward-most group's actual world
        // X, not from the seed. {@code TrainCarriageAppender.onLevelTick}
        // (per-tick appender) may run in the same tick BEFORE this eager
        // fill — its spawns extend the train by 1+ groups in each direction
        // before we get control. Initialising the rolling ref X from the
        // seed would then place our first eager spawn on TOP of the
        // per-tick-added group at the same X — the overlap observed in
        // the field. Look up the live world X for {@code trainMax} and
        // {@code trainMin} from the registry instead.
        double forwardRefX = worldXOfAnchor(trainId, trainMax);
        double backwardRefX = worldXOfAnchor(trainId, trainMin);
        if (Double.isNaN(forwardRefX) || Double.isNaN(backwardRefX)) {
            LOGGER.warn("[DungeonTrain] Bootstrap eager fill: could not resolve world X for edge anchors trainMax={} trainMin={} trainId={} — skipping",
                trainMax, trainMin, trainId);
            return;
        }

        // iterCap sized to cover the full needed range. Each loop body spawns
        // up to one forward + one backward group, so total iterations needed
        // is at most ceil((neededMax - neededMin + 1) / (2 * groupSize)) + a
        // small slack. Use a generous overestimate so the cap never truncates
        // a legitimate fill at high render distance.
        int neededSpan = Math.max(1, neededMax - neededMin + 1);
        int iterCap = (neededSpan / Math.max(1, groupSize)) + 8;
        int spawned = 0;
        Vector3dc velocity = realVelocity;

        // Pre-warm chunks for the full planned eager-fill footprint. Without
        // this, the first clearSubLevelVolume / placeAt inside each spawnGroup
        // pays a synchronous worldgen wait for every fresh chunk it touches —
        // the same root cause TrackGenerator already mitigates (see comment at
        // TrackGenerator placeTrackColumn: "500ms tracks= spikes when flying
        // over freshly-streaming chunks"). One up-front pass pays the worldgen
        // wait once per chunk instead of once per touch.
        int forwardGroupsToSpawn = Math.max(0,
            (neededMax - trainMax + groupSize - 1) / groupSize);
        int backwardGroupsToSpawn = Math.max(0,
            (trainMin - neededMin + groupSize - 1) / groupSize);
        int gapPerGroupCeil = (int) Math.ceil(EAGER_FILL_GAP_BLOCKS);
        int forwardReach = forwardGroupsToSpawn * (subLevelStride + gapPerGroupCeil);
        int backwardReach = backwardGroupsToSpawn * (subLevelStride + gapPerGroupCeil);
        int prewarmXMin = (int) Math.floor(backwardRefX) - backwardReach - subLevelStride;
        int prewarmXMax = (int) Math.ceil(forwardRefX) + forwardReach + subLevelStride;
        int prewarmZMin = placeZ;
        int prewarmZMax = placeZ + dims.width() - 1;
        prewarmEagerFillChunks(level, prewarmXMin, prewarmXMax, prewarmZMin, prewarmZMax);

        // Spawn one group in BOTH directions per iteration so the train
        // grows symmetrically (forward AND backward together) rather than
        // filling one end completely before the other starts. Each iteration
        // is at most two TrainAssembler.spawnGroup calls. The
        // {@link #TARGET_EAGER_GAP_BLOCKS} gap is applied per seam by walking
        // the rolling forward/backward reference X outward (fractional part
        // carried into the group transform via preSeedGapShift).
        for (int iter = 0; iter < iterCap; iter++) {
            boolean needsForward = trainMax < neededMax;
            boolean needsBackward = trainMin > neededMin;
            if (!needsForward && !needsBackward) break;

            if (needsForward) {
                int forwardAnchor = trainMax + groupSize;
                if (Trains.knownAnchors(trainId).contains(forwardAnchor)) {
                    LOGGER.warn("[DungeonTrain] Bootstrap eager fill: refused to re-spawn known forward anchor={} trainId={}", forwardAnchor, trainId);
                    break;
                }
                // Desired world X for the exact TARGET_EAGER_GAP_BLOCKS gap.
                // Place blocks at the NEAREST integer and carry the leftover
                // fraction into the group's world transform so the seam lands
                // at the sub-block target instead of a quantised whole block.
                // Chain forwardRefX through the true (fractional) desired X, NOT
                // the rounded placeX, so per-group offsets don't accumulate.
                double desiredWorldX = forwardRefX + subLevelStride + TARGET_EAGER_GAP_BLOCKS;
                int placeX = (int) Math.round(desiredWorldX);
                double remainderX = desiredWorldX - placeX;
                BlockPos newOrigin = new BlockPos(placeX, placeY, placeZ);
                ManagedShip newShip = TrainAssembler.spawnGroup(level, newOrigin, velocity, forwardAnchor, groupSize, dims, trainId);
                preSeedGapShift(newShip, remainderX);
                markEagerFilledPlaced(newShip);
                // Keep resident until serialized — bootstrap groups are the
                // source of the unrecoverable null-pointer ghosts that dead-end
                // backward generation (they cull before any save). Held here so
                // a later cull stays reloadable.
                holdGroupResident(level, trainId, newShip);
                BootstrapProgress.advance(groupSize);
                forwardRefX = desiredWorldX;
                trainMax = forwardAnchor;
                spawned++;
            }
            if (needsBackward) {
                int backwardAnchor = trainMin - groupSize;
                if (Trains.knownAnchors(trainId).contains(backwardAnchor)) {
                    LOGGER.warn("[DungeonTrain] Bootstrap eager fill: refused to re-spawn known backward anchor={} trainId={}", backwardAnchor, trainId);
                    break;
                }
                // Mirror of the forward branch (see there): nearest-integer
                // block origin + fractional remainder carried into the world
                // transform, chaining backwardRefX through the true desired X.
                double desiredWorldX = backwardRefX - subLevelStride - TARGET_EAGER_GAP_BLOCKS;
                int placeX = (int) Math.round(desiredWorldX);
                double remainderX = desiredWorldX - placeX;
                BlockPos newOrigin = new BlockPos(placeX, placeY, placeZ);
                ManagedShip newShip = TrainAssembler.spawnGroup(level, newOrigin, velocity, backwardAnchor, groupSize, dims, trainId);
                preSeedGapShift(newShip, remainderX);
                markEagerFilledPlaced(newShip);
                holdGroupResident(level, trainId, newShip); // keep resident until serialized (see forward branch)
                BootstrapProgress.advance(groupSize);
                backwardRefX = desiredWorldX;
                trainMin = backwardAnchor;
                spawned++;
            }
        }

        // Lock the eager-filled layout as committed: every carriage in this
        // train (seed + just-spawned) is marked placedSuccessfully so the
        // per-tick collision tracker NEVER shifts them via shiftSpawnPosition.
        // The eager-fill guarantees non-overlap by construction (deterministic
        // stride + TARGET_EAGER_GAP_BLOCKS gap), so the tracker's nudge logic
        // would only break the carefully-spaced layout. With all carriages
        // exempt, they move in lockstep via their shared targetVelocity and
        // the deterministic canonicalPos = spawnWorldPos + velocity*elapsedTicks
        // formula — the train behaves as one rigid group, exactly what
        // dropping them all in at once requires to stay stable.
        //
        // Iterates {@code Trains.knownGroups} (synchronous spawn-time
        // registry) instead of {@code Trains.findById} — Sable hasn't bound
        // any of these sub-levels into Shipyards.findAll() yet at bootstrap,
        // so a Shipyards-backed lookup would return nothing.
        for (ManagedShip ship : Trains.knownGroups(trainId).values()) {
            if (ship.getKinematicDriver() instanceof TrainTransformProvider p
                && !p.isPlacedSuccessfully()) {
                p.markPlacedSuccessfully();
            }
        }

        LOGGER.info("[DungeonTrain] Bootstrap eager fill on trainId={}: playerPIdx={} target={} need=[{},{}] spawned {} group(s), anchor range [{},{}] -> [{},{}] (groupSize={})",
            trainId, playerPIdx, targetCount, neededMin, neededMax, spawned, startMin, startMax, trainMin, trainMax, groupSize);
    }

    /**
     * Resolve the current world X of a specific anchor by looking up its
     * registered {@link ManagedShip} and {@code shipToWorld}-ing its
     * stored shipyard origin. Used by {@link #eagerFillForBootstrap} to
     * initialise the rolling forward/backward reference X from the actual
     * train edges as registered, rather than recomputing from the seed.
     *
     * <p>Returns {@link Double#NaN} if the anchor isn't in the registry or
     * its driver isn't a {@link TrainTransformProvider} — the caller bails
     * in that case.</p>
     */
    private static double worldXOfAnchor(UUID trainId, int anchor) {
        ManagedShip ship = Trains.knownGroups(trainId).get(anchor);
        if (ship == null) return Double.NaN;
        if (!(ship.getKinematicDriver() instanceof TrainTransformProvider provider)) return Double.NaN;
        BlockPos shipyardOrigin = provider.getShipyardOrigin();
        Vector3d worldOrigin = new Vector3d(shipyardOrigin.getX(), shipyardOrigin.getY(), shipyardOrigin.getZ());
        ship.shipToWorld(worldOrigin);
        return worldOrigin.x;
    }

    /**
     * Force every chunk in the rectangle {@code [xMin..xMax] × [zMin..zMax]} to
     * {@link ChunkStatus#FULL} synchronously before the eager-fill spawn loop
     * runs. Each {@code spawnGroup} subsequently performs ~1800 setBlock /
     * getBlockState calls inside its footprint; without pre-warm, those calls
     * hitting a not-yet-FULL chunk pay a per-block synchronous worldgen wait
     * (the same pathology TrackGenerator avoids with a similar deferred-load
     * pattern). Pre-warming amortises the worldgen cost: each chunk is brought
     * to FULL exactly once, and the subsequent block-touches are cheap.
     */
    private static void prewarmEagerFillChunks(
        ServerLevel level, int xMin, int xMax, int zMin, int zMax
    ) {
        // ±1-chunk margin — the same quiescence rule as the per-tick spawn gate
        // (ensureSpawnFootprintReady). Forcing the footprint's neighbours to FULL
        // too guarantees no worker light task reads a footprint column while the
        // eager-fill loop stamps it section-local. (The Z bounds arrive with no
        // margin — prewarmZMin/Max are exactly the write width — so this is load-
        // bearing on Z, not merely defensive.)
        int cxMin = (xMin >> 4) - 1;
        int cxMax = (xMax >> 4) + 1;
        int czMin = (zMin >> 4) - 1;
        int czMax = (zMax >> 4) + 1;
        long t0 = System.nanoTime();
        int loaded = 0;
        for (int cx = cxMin; cx <= cxMax; cx++) {
            for (int cz = czMin; cz <= czMax; cz++) {
                WorldgenForceGuard.forceChunk(level, cx, cz);
                loaded++;
            }
        }
        LOGGER.info("[DungeonTrain] Eager fill pre-warmed {} chunks (X[{},{}] Z[{},{}]) in {}ms",
            loaded, cxMin, cxMax, czMin, czMax, (System.nanoTime() - t0) / 1_000_000);
    }

    /**
     * Mark a just-eager-spawned group's {@link TrainTransformProvider} as
     * {@code placedSuccessfully}. Eager-filled groups skip the per-tick
     * collision tracker by design (the deterministic stride + gap math
     * guarantees non-overlap), so we set the flag immediately rather than
     * waiting for the tracker's {@code CLEAN_TICKS_FOR_SUCCESS} window. Also
     * unlocks the deferred contents-entity spawn gate which short-circuits
     * on {@code !isPlacedSuccessfully} (see {@link #tickPendingEntitySpawnDistanceGate}).
     */
    private static void markEagerFilledPlaced(ManagedShip newShip) {
        if (newShip.getKinematicDriver() instanceof TrainTransformProvider provider) {
            provider.markPlacedSuccessfully();
        }
    }

    /**
     * Pre-seed a just-spawned eager-fill group with a sub-block world-X offset
     * so its seam lands at exactly {@link #TARGET_EAGER_GAP_BLOCKS} rather than
     * the whole-block gap forced by the integer {@code BlockPos} spawn origin.
     * The offset is applied once, when the group's {@code spawnWorldPos} is
     * captured on its first kinematic tick (see
     * {@link TrainTransformProvider#preSeedSpawnShiftX}). A zero remainder is a
     * cheap no-op. Skipped silently if the ship has no transform provider.
     */
    private static void preSeedGapShift(ManagedShip newShip, double dx) {
        if (newShip != null
            && newShip.getKinematicDriver() instanceof TrainTransformProvider provider) {
            provider.preSeedSpawnShiftX(dx);
        }
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
     * Bootstrap eager-fill variant: same {@code × 16 × 2 / length} math as
     * {@link #autoTargetFromRenderDistance} but sourced from the server's
     * {@code view-distance} property (via
     * {@code level.getServer().getPlayerList().getViewDistance()}) rather
     * than any individual player — at bootstrap time no player has joined
     * yet. Falls back to a hardcoded 10-chunk floor if the server reports 0
     * (defensive; in practice the server-properties default is always set).
     *
     * <p>WITHOUT the {@link DungeonTrainConfig#MAX_CARRIAGES} ceiling — the
     * per-tick appender caps each player's rolling window at 50 carriages
     * because letting an uncapped window grow under chunk-render-distance
     * pressure could thrash Sable's plot loader during gameplay, but at
     * world load we want to fill what the player will actually see.</p>
     *
     * <p>Still respects {@link DungeonTrainConfig#MIN_CARRIAGES_AUTO_FLOOR}
     * so very-low view-distance values still produce a sensible train.</p>
     */
    private static int bootstrapTargetFromServerViewDistance(ServerLevel level, int carriageLength) {
        int rdChunks = level.getServer().getPlayerList().getViewDistance();
        if (rdChunks <= 0) rdChunks = 10;
        int rdBlocks = rdChunks * 16;
        int target = (rdBlocks * 2) / Math.max(1, carriageLength);
        return Math.max(DungeonTrainConfig.MIN_CARRIAGES_AUTO_FLOOR, target);
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
    /**
     * DT-private chunk ticket that pulls a spawn footprint's world chunks to
     * {@code FULL} asynchronously. Self-expiring ({@code 200}-tick lifespan) so
     * it never needs manual release — by the time it lapses the carriage has
     * spawned (adding its own hold) or the train has moved on. Distinct type so
     * it never collides with vanilla or Sable tickets.
     */
    private static final TicketType<ChunkPos> SPAWN_PREGEN_TICKET =
        TicketType.create("dungeontrain_spawn_pregen", Comparator.comparingLong(ChunkPos::toLong), 200);

    /**
     * Per-train, per-direction game-tick at which we first deferred a spawn
     * waiting for its footprint chunks to finish generating. Bounds the wait via
     * {@link #SPAWN_GEN_WAIT_MAX_TICKS} so a train can never permanently stop
     * extending if generation stalls. Cleared the tick the footprint is ready.
     */
    private static final Map<UUID, Long> SPAWN_GEN_WAIT_FORWARD = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SPAWN_GEN_WAIT_BACKWARD = new ConcurrentHashMap<>();

    /**
     * Max ticks a spawn may defer waiting for async footprint generation before it may fire the
     * synchronous-gen backstop. ~15 s at 20 TPS. Deliberately generous: a ~3-chunk footprint
     * completes in a handful of ticks on flat overworld, but while a moving train crosses the
     * expensive Nether-transition band the worldgen workers are saturated and async gen legitimately
     * takes many seconds — the old 5 s value fired the sync backstop during that *normal* heavy load,
     * blocking the server thread. At 15 s the backstop only trips on a genuine async stall, not
     * ordinary band-crossing backpressure.
     */
    private static final long SPAWN_GEN_WAIT_MAX_TICKS = 300L;

    /**
     * Server-wide minimum spacing (ticks) between two synchronous {@link #forceRegionFullChunks}
     * backstops, across ALL trains and both spawn lanes. The 16.85 s reversal freeze was two lanes'
     * blocking {@code getChunk(FULL)} joins <em>stacking</em> in one tick while the worldgen queue was
     * saturated. This budget lets at most one forced sync-gen fire per window; every other lane keeps
     * deferring (async ticket stays live) instead of piling a second multi-second main-thread join on
     * top. ~10 s at 20 TPS.
     */
    private static final long SYNC_GEN_COOLDOWN_TICKS = 200L;

    /**
     * Game-tick of the most recent synchronous {@link #forceRegionFullChunks} backstop, server-wide
     * (all trains / both lanes). Guards {@link #SYNC_GEN_COOLDOWN_TICKS}. {@code Long.MIN_VALUE} = none
     * yet; reset on {@link #clearSettleTracker} so a new world starts with the budget available.
     * Server-thread only (the appender spawn loop), so a plain field is sufficient.
     */
    private static long lastSyncGenTick = Long.MIN_VALUE;

    /**
     * True once every world chunk under {@code plan}'s footprint is loaded to at
     * least {@code FULL} (so {@link TrainAssembler#spawnGroup}'s block reads/writes
     * won't trigger a synchronous world-gen on this tick — the dominant appender
     * spike). When not ready, kicks off asynchronous generation via a
     * {@link #SPAWN_PREGEN_TICKET} and returns {@code false} so the caller defers
     * the spawn to a later tick — <em>unless</em> the per-direction wait has
     * exceeded {@link #SPAWN_GEN_WAIT_MAX_TICKS}, in which case it forces the
     * region to {@link ChunkStatus#FULL} synchronously (see
     * {@link #forceRegionFullChunks}) and returns {@code true} so the train never
     * permanently stalls — without the unsafe concurrent section write.
     *
     * <p>The checked region is the footprint columns plus a 1-chunk margin so no
     * async light task for a footprint chunk's neighbour can read a footprint
     * section while it is being stamped section-local.</p>
     */
    private static boolean ensureSpawnFootprintReady(ServerLevel level, Plan plan, UUID trainId, boolean forward, long now) {
        int xMin = plan.origin.getX();
        int xMax = xMin + plan.subLevelStride - 1;
        int zMin = plan.origin.getZ();
        int zMax = zMin + plan.sizeZ - 1;
        // Quiescence region = the footprint columns PLUS a 1-chunk margin. A
        // section-local write into a footprint column (SilentBlockOps.
        // setBlockSectionLocal, the lock-skipping section.setBlockState(...,false))
        // is only safe once that column AND its immediate neighbours are FULL:
        // block light propagates ≤15 blocks (<1 chunk), so an async light task for
        // a footprint chunk's NEIGHBOUR still reads the footprint chunk's sections.
        // Writing while such a worker runs grows the LinearPalette in place against
        // its lock-free read → MissingPaletteEntryException on the worker → the
        // failed chunk future stalls downstream gen → server-tick soft-hang. Gating
        // on footprint±1 FULL guarantees no worker light task reads a footprint
        // column while we stamp it. (useLocks=true does NOT help — the reader never
        // locks; see SilentBlockOps.setBlockSectionLocal.)
        int cxMin = (xMin >> 4) - 1, cxMax = (xMax >> 4) + 1;
        int czMin = (zMin >> 4) - 1, czMax = (zMax >> 4) + 1;

        boolean allFull = true;
        for (int cx = cxMin; cx <= cxMax && allFull; cx++) {
            for (int cz = czMin; cz <= czMax; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) { allFull = false; break; }
            }
        }

        Map<UUID, Long> waitMap = forward ? SPAWN_GEN_WAIT_FORWARD : SPAWN_GEN_WAIT_BACKWARD;
        if (allFull) {
            waitMap.remove(trainId);
            return true;
        }

        // Not generated yet — request async generation and defer this tick.
        requestFootprintGen(level, cxMin, cxMax, czMin, czMax);
        long waitedSince = waitMap.computeIfAbsent(trainId, k -> now);
        if (now - waitedSince >= SPAWN_GEN_WAIT_MAX_TICKS) {
            // Backstop: async gen has stalled for the whole (generous) timeout. We must NOT
            // "spawn anyway" with section-local writes — that races the still-running light workers
            // and tears the palette (the soft-hang this gate exists to prevent). The only safe
            // recoveries are to keep deferring or to force the region to FULL synchronously (blocking
            // getChunk(FULL) drives gen AND drains light here, so afterwards nothing reads those
            // sections and the spawn's section-local writes have no concurrent reader).
            //
            // A synchronous force is a multi-second main-thread join while the worldgen queue is
            // saturated (a train reversal at the Nether band). To stop two lanes/trains from STACKING
            // those joins in one tick (the 16.85 s freeze), gate the force behind a server-wide budget:
            // fire at most one per SYNC_GEN_COOLDOWN_TICKS. If the budget isn't available, keep
            // deferring — the async ticket stays live (re-requested above) and almost always completes
            // before the next window, so the sync spike is avoided entirely rather than piled on.
            // (now < lastSyncGenTick guards a world reload resetting the game clock backwards.)
            boolean budgetAvailable = now - lastSyncGenTick >= SYNC_GEN_COOLDOWN_TICKS || now < lastSyncGenTick;
            if (!budgetAvailable) {
                return false;                                  // defer; another lane holds the sync budget this window
            }
            lastSyncGenTick = now;
            LOGGER.warn("[DungeonTrain] Spawn footprint gen wait timed out ({} ticks) trainId={} forward={} — forcing footprint+margin chunks X[{},{}] Z[{},{}] to FULL synchronously (one sync-gen spike)",
                now - waitedSince, trainId, forward, cxMin, cxMax, czMin, czMax);
            forceRegionFullChunks(level, cxMin, cxMax, czMin, czMax);
            waitMap.remove(trainId);
            return true;
        }
        return false;
    }

    /**
     * Add a self-expiring async-generation ticket over the quiescence region
     * (footprint + 1-chunk margin) so the {@link #ensureSpawnFootprintReady} gate
     * can be satisfied asynchronously — including the margin — without a sync-gen
     * spike.
     */
    private static void requestFootprintGen(ServerLevel level, int cxMin, int cxMax, int czMin, int czMax) {
        var chunkSource = level.getChunkSource();
        for (int cx = cxMin; cx <= cxMax; cx++) {
            for (int cz = czMin; cz <= czMax; cz++) {
                ChunkPos pos = new ChunkPos(cx, cz);
                // radius 1 → the chunk is pulled to BLOCK_TICKING (≥ FULL),
                // dragging the neighbours it needs to reach FULL along with it.
                chunkSource.addRegionTicket(SPAWN_PREGEN_TICKET, pos, 1, pos);
            }
        }
    }

    /**
     * Force every chunk in {@code [cxMin..cxMax] × [czMin..czMax]} to
     * {@link ChunkStatus#FULL} synchronously on the server thread. Blocking
     * {@code getChunk(FULL)} both drives generation and drains that chunk's light
     * here rather than on a worker, so after this returns no async gen/light task
     * is reading those sections — a subsequent section-local write cannot race a
     * lock-free worker read. Used only by the {@link #ensureSpawnFootprintReady}
     * timeout backstop, so the bounded sync-gen spike is paid only when async gen
     * has stalled. Mirrors {@link #prewarmEagerFillChunks} but takes chunk coords.
     */
    private static void forceRegionFullChunks(ServerLevel level, int cxMin, int cxMax, int czMin, int czMax) {
        long t0 = System.nanoTime();
        int loaded = 0;
        for (int cx = cxMin; cx <= cxMax; cx++) {
            for (int cz = czMin; cz <= czMax; cz++) {
                WorldgenForceGuard.forceChunk(level, cx, cz);
                loaded++;
            }
        }
        LOGGER.info("[DungeonTrain] Spawn backstop forced {} chunks (X[{},{}] Z[{},{}]) to FULL in {}ms",
            loaded, cxMin, cxMax, czMin, czMax, (System.nanoTime() - t0) / 1_000_000);
    }

    /**
     * Create the group described by {@code plan}: chunk-readiness gate, Sable
     * assembly, sub-block seam nudge, then the neighbour collision marking.
     *
     * <p>Takes the {@link Plan} rather than computing it so a caller can plan
     * once and reuse the result — the two spawn lanes plan against the registry
     * edge via {@link #planSpawnPlacement}, while a catch-up burst chains the
     * previous group's plan via {@link #planChainedSpawn}.</p>
     *
     * @param reference the carriage the plan was placed against, used ONLY by
     *     the opt-in {@code [bwd-place]} diagnostic; {@code null} for a chained
     *     burst group, whose reference was spawned this same tick and has no
     *     meaningful pose yet
     * @return the spawned group's {@link ManagedShip}, or {@code null} if the
     *     spawn was <b>deferred</b> this tick because its footprint chunks are
     *     still generating (see {@link #ensureSpawnFootprintReady}). A {@code null}
     *     return means "nothing spawned, retry next tick"; callers must skip all
     *     post-spawn bookkeeping.
     */
    private static ManagedShip spawnPlannedGroup(
        ServerLevel level,
        Plan plan,
        Trains.Carriage reference,
        int newAnchor,
        int groupSize,
        CarriageDims dims,
        Vector3dc velocity,
        UUID trainId,
        List<Trains.Carriage> train
    ) {
        // Chunk-readiness gate — keep synchronous world-generation off the spawn
        // tick (profiled as the dominant appender spike: a cold forward spawn's
        // getBlockState scan forces ~170–280 ms of world-gen). If the footprint
        // isn't generated yet, kick off async gen and defer to a later tick.
        if (!ensureSpawnFootprintReady(level, plan, trainId, plan.forward, level.getGameTime())) {
            return null;
        }

        ManagedShip newShip = TrainAssembler.spawnGroup(
            level, plan.origin, velocity, newAnchor, groupSize, dims, trainId);
        // Land the seam on TARGET_GAP_BLOCKS rather than the integer origin's quantisation of it,
        // so the group starts inside the tracker's clean dead-band (see planSpawnPlacement).
        preSeedGapShift(newShip, plan.preSeedRemainderX);

        LOGGER.info("[DungeonTrain] Appender added group anchorPIdx={} groupSize={} trainId={} ship id={} placedAt={} (idealX={}, dir={}, gapBlocks={}, subLevelStride={}, collisionAdjustments={})",
            newAnchor, groupSize, trainId, newShip.id(), plan.origin,
            String.format("%.4f", plan.idealX),
            plan.forward ? "forward" : "backward",
            String.format("%.4f", plan.gap),
            plan.subLevelStride,
            plan.collisionAdjustments);

        // [bwd-place] diagnostic (opt-in, backward spawns only). Decomposes the
        // idealX placement math so a growing seam gap can be attributed: H1 is
        // confirmed iff subLevelDelta is consistently NOT -1 (refAnchor diverged
        // from registryMin); H5 iff refShipToWorldX and refWorldAabbMaxX disagree
        // by more than a tick-step; H3 iff offenderRegistryOnly=true on an
        // inflated seam.
        if (SEAMGAP_TRACE_ENABLED && !plan.forward) {
            int registryMin = Integer.MAX_VALUE;
            int registryMax = Integer.MIN_VALUE;
            for (int a : Trains.knownAnchors(trainId)) {
                if (a < registryMin) registryMin = a;
                if (a > registryMax) registryMax = a;
            }
            // NaN for a chained burst group: its reference was spawned this tick
            // and Sable has not given it a pose yet (see the parameter javadoc).
            double refWorldAabbMaxX = Double.NaN;
            if (reference != null) {
                AABBdc refAabb = reference.ship().worldAABB();
                refWorldAabbMaxX = isZeroAabb(refAabb) ? Double.NaN : refAabb.maxX();
            }
            LOGGER.info("[DungeonTrain][bwd-place] gameTick={} trainId={} newAnchor={} refAnchor={} registryMin={} registryMax={} subLevelDelta={} subLevelStride={} refShipToWorldX={} refWorldAabbMaxX={} idealX={} initialPlaceX={} adjustedPlaceX={} collisionAdjustments={} offenderPIdx={} offenderRegistryOnly={}",
                level.getGameTime(), trainId, newAnchor, plan.refAnchor,
                registryMin, registryMax, plan.subLevelDelta, plan.subLevelStride,
                String.format("%.4f", plan.refShipToWorldX),
                String.format("%.4f", refWorldAabbMaxX),
                String.format("%.4f", plan.idealX),
                plan.initialPlaceX, plan.adjustedPlaceX, plan.collisionAdjustments,
                plan.lastOffenderPIdx, plan.lastOffenderRegistryOnly);
        }

        markCollidingNeighbours(level, newShip, newAnchor, train);
        return newShip;
    }

    /**
     * Post-spawn bookkeeping shared by both lanes and by every group of a
     * {@link #spawnCatchUpBurst}: stamp the direction flag, arm the lane's
     * in-flight placement gate, register the post-spawn collision check, hold
     * the group resident, and announce it.
     *
     * <p>The hold is what stops Sable's per-tick simulation-distance cull from
     * dropping a just-spawned group — at low sim distance, the SAME tick it
     * appears, while it sits outside the ticking bubble — into a holding entry
     * with a null serialization pointer that {@code reloadFromHolding} can't
     * revive, permanently losing the carriage (the "train vanishes on autosave"
     * report). It self-drains via {@code reconcileForceLoads} once the next
     * save mints a pointer (bounded by autosave cadence); after that a cull
     * lands in reloadable holding — the "reloads on approach" the old behaviour
     * intended, now made recoverable. The backward lane force-loads at the
     * spawn site instead, because the new trailing group isn't in the visible
     * train yet this tick and so the per-tick trailing window can't cover it.
     * Both are gated on the same {@link #shouldHoldSpawnedGroup} policy, which
     * is what keeps the two lanes provably symmetric at a single tested
     * decision point.</p>
     *
     * <p>Called once per spawned group, so a burst's LAST group ends up in
     * {@code LAST_SPAWNED_SHIP_*} — the gate then waits on the outermost
     * carriage, which is exactly the one the next spawn is placed against.</p>
     */
    private static void recordSpawnedGroup(
        ServerLevel level,
        UUID trainId,
        ManagedShip newShip,
        int anchor,
        List<Trains.Carriage> train,
        long now,
        boolean forward
    ) {
        if (newShip.getKinematicDriver() instanceof TrainTransformProvider newProvider) {
            newProvider.setSpawnedBackward(!forward);
        }
        if (forward) {
            LAST_SPAWNED_SHIP_FORWARD.put(trainId, newShip);
            LAST_SPAWNED_TICK_FORWARD.put(trainId, now);
        } else {
            LAST_SPAWNED_SHIP_BACKWARD.put(trainId, newShip);
            LAST_SPAWNED_TICK_BACKWARD.put(trainId, now);
        }
        recordPostSpawnCollisionCheck(trainId, newShip, anchor, train);
        if (shouldHoldSpawnedGroup(forward)) {
            if (forward) holdGroupResident(level, trainId, newShip);
            else forceLoadSpawnedBackward(level, trainId, newShip);
        }
        announceSpawn(level, anchor);
    }

    /**
     * Spawn a lane's follow-on catch-up groups, each chained off the PLANNED
     * placement of the group before it. No-op (returns 0) unless
     * {@link #catchUpBurstGroups} says this lane is behind — so the steady
     * state keeps the one-group-per-settle-window cadence exactly as before.
     *
     * <p>Every extra group repeats the lane's own guards: the anchor is
     * re-checked against {@link Trains#knownAnchors} (never re-spawn an anchor
     * the registry owns), and a {@code null} from {@link #spawnPlannedGroup}
     * (footprint chunks still generating) simply ends the burst — the lane
     * retries on a later tick as it always has. Burst groups stay under the
     * normal {@link #runPlacementCollisionTracker}, so its proportional shift
     * and {@link #SHIFT_SETTLE_TICKS} cooldown still correct a seam that lands
     * outside the band.</p>
     *
     * @param firstPlan  the plan of the group the lane just spawned
     * @param firstAnchor that group's anchor pIdx
     * @param deficitPIdx how far this lane is behind, in carriage indices
     * @return how many EXTRA groups were spawned (0 when not catching up)
     */
    private static int spawnCatchUpBurst(
        ServerLevel level,
        UUID trainId,
        List<Trains.Carriage> train,
        ManagedShip firstShip,
        Plan firstPlan,
        int firstAnchor,
        int deficitPIdx,
        int groupSize,
        CarriageDims dims,
        Vector3dc velocity,
        long now,
        boolean forward
    ) {
        int allowed = catchUpBurstGroups(deficitPIdx, groupSize);
        if (allowed <= 1) return 0;

        Plan prevPlan = firstPlan;
        int prevAnchor = firstAnchor;
        UUID prevSubLevelId = firstShip.subLevelId();
        int extra = 0;
        for (int i = 1; i < allowed; i++) {
            int nextAnchor = forward ? (prevAnchor + groupSize) : (prevAnchor - groupSize);
            if (Trains.knownAnchors(trainId).contains(nextAnchor)) {
                LOGGER.debug("[DungeonTrain] Catch-up burst: skipping already-spawned anchor={} for trainId={} (in registry)",
                    nextAnchor, trainId);
                break;
            }
            Plan chained = planChainedSpawn(prevPlan, prevAnchor, nextAnchor, dims, train, trainId);
            if (!burstChainIsCommittable(chained.collisionAdjustments())) {
                LOGGER.info("[DungeonTrain] Catch-up burst: chained anchor={} needed a {}-block collision shove — abandoning burst (trainId={})",
                    nextAnchor, chained.collisionAdjustments(), trainId);
                break;
            }
            ManagedShip extraShip = spawnPlannedGroup(
                level, chained, null, nextAnchor, groupSize, dims, velocity, trainId, train);
            if (extraShip == null) break;
            recordSpawnedGroup(level, trainId, extraShip, nextAnchor, train, now, forward);
            // Link it to the group it was chained off, so any placement-tracker
            // shift applied up-chain moves this one by the same dx and the
            // intra-burst seam survives (see BURST_FOLLOWERS).
            if (extraShip.getKinematicDriver() instanceof TrainTransformProvider extraProvider) {
                linkBurstFollower(prevSubLevelId, extraShip.subLevelId(), extraProvider);
            }
            prevSubLevelId = extraShip.subLevelId();
            prevPlan = chained;
            prevAnchor = nextAnchor;
            extra++;
        }

        if (extra > 0) {
            LOGGER.info("[DungeonTrain] Catch-up burst on lane {}: deficitPIdx={} groupSize={} extraGroups={} anchors {}..{} trainId={}",
                forward ? "forward" : "backward",
                deficitPIdx, groupSize, extra, firstAnchor, prevAnchor, trainId);
        }
        return extra;
    }

    /**
     * Plan the next group of a {@link #spawnCatchUpBurst}, chained off the
     * PLANNED placement of the group before it rather than off a live pose.
     *
     * <p>{@link #planSpawnPlacement} can't be used here: it derives
     * {@code idealX} from {@code reference.ship().shipToWorld(...)}, and the
     * previous burst group was created this same tick —
     * {@link TrainAssembler#spawnGroup} deliberately leaves its
     * {@code spawnWorldPos} unseeded until Sable's first kinematic tick, so its
     * transform is not yet meaningful. Chaining the previous group's own
     * placement instead is exactly what {@link #eagerFillForBootstrap} does
     * when it drops a whole train in one tick (its rolling
     * {@code forwardRefX}/{@code backwardRefX}), and it makes the seam
     * deterministic: one whole {@code subLevelStride} plus
     * {@link #TARGET_GAP_BLOCKS}, the centre of the placement tracker's clean
     * dead-band, so the group starts in band and needs no shift pass.</p>
     *
     * <p>{@link #adjustForCollisions} still runs, for the same reason the
     * normal path runs it — a stale/ghost sibling box could sit in the way. The
     * previous burst group is not among the boxes it can see (a fresh ship's
     * {@code worldAABB} is zero and zero-AABB siblings are skipped), which is
     * precisely why the placement must come from the chained stride.</p>
     */
    private static Plan planChainedSpawn(
        Plan previous,
        int previousAnchor,
        int newAnchor,
        CarriageDims dims,
        List<Trains.Carriage> train,
        UUID trainId
    ) {
        boolean forward = previous.forward();
        int subLevelStride = previous.subLevelStride();
        // Where the previous group actually ended up, sub-block nudge included.
        double previousEffectiveX = previous.adjustedPlaceX() + previous.preSeedRemainderX();
        // Abutting (zero-gap) origin, mirroring planSpawnPlacement's idealX.
        double idealX = forward
            ? (previousEffectiveX + subLevelStride)
            : (previousEffectiveX - subLevelStride);
        double desiredWorldX = chainedSpawnDesiredX(previousEffectiveX, subLevelStride, forward);

        int initialPlaceX = (int) Math.round(desiredWorldX);
        int placeY = previous.origin().getY();
        int placeZ = previous.origin().getZ();

        CollisionAdjustResult adjusted = adjustForCollisions(
            initialPlaceX, placeY, placeZ, subLevelStride, dims, train, trainId, forward, newAnchor);
        int adjustedPlaceX = adjusted.placeX();

        // Same rule as planSpawnPlacement: drop the sub-block remainder if the
        // collision pass moved the origin deliberately.
        double preSeedRemainderX = (adjustedPlaceX == initialPlaceX)
            ? (desiredWorldX - initialPlaceX)
            : 0.0;

        BlockPos origin = new BlockPos(adjustedPlaceX, placeY, placeZ);
        double effectivePlaceX = adjustedPlaceX + preSeedRemainderX;
        double gap = forward ? (effectivePlaceX - idealX) : (idealX - effectivePlaceX);

        return new Plan(
            origin,
            subLevelStride,
            dims.height(),
            dims.width(),
            idealX,
            gap,
            forward,
            adjustedPlaceX - initialPlaceX,
            previousAnchor,
            previousEffectiveX,
            forward ? 1 : -1,
            initialPlaceX,
            adjustedPlaceX,
            adjusted.lastOffenderPIdx(),
            adjusted.lastOffenderRegistryOnly(),
            preSeedRemainderX);
    }

    /**
     * Whether a chained burst group may be committed, given how far
     * {@link #adjustForCollisions} had to move it off the placement
     * {@link #planChainedSpawn} computed.
     *
     * <p>Only an untouched placement counts. The chain's whole premise is that
     * the previous group landed exactly where it was planned, so the next one
     * can be derived from that plan rather than from a pose Sable hasn't
     * written yet. The moment the collision pass has to shove the chained group,
     * that premise is already false — something is sitting where the stride
     * said the group goes — and the honest answer is to abandon the burst and
     * let the lane re-plan against the registry edge on a later tick at its
     * normal cadence.</p>
     *
     * <p>Observed in play (2026-08-30, backward lane): a group whose registry
     * edge had diverged ~190 blocks from the previous group's real position
     * produced a chained plan the collision pass moved <b>116 blocks</b>, and
     * the burst committed it — a carriage-sized hole in the train. The
     * divergence itself is a separate, pre-existing backward-edge bug; this
     * guard stops the burst from turning it into a placed group.</p>
     */
    static boolean burstChainIsCommittable(int collisionAdjustments) {
        return collisionAdjustments == 0;
    }

    /**
     * World X a chained catch-up group's origin should land on so its seam
     * against the group before it measures exactly {@link #TARGET_GAP_BLOCKS}:
     * one whole sub-level stride beyond that group's effective origin, plus
     * (forward) or minus (backward) the target gap.
     *
     * <p>Split out as a pure helper so the chained stride is unit-testable
     * without a level (mirrors {@link #preSeedDesiredX}).</p>
     */
    static double chainedSpawnDesiredX(double previousEffectiveX, int subLevelStride, boolean forward) {
        return forward
            ? (previousEffectiveX + subLevelStride + TARGET_GAP_BLOCKS)
            : (previousEffectiveX - subLevelStride - TARGET_GAP_BLOCKS);
    }

    /**
     * Pure-ish placement helper: replays {@link #spawnPlannedGroup}'s ideal-X
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
        // Aim the seam at TARGET_GAP_BLOCKS and carry the sub-block leftover in the group's world
        // transform (preSeedGapShift), exactly as the bootstrap eager fill does. The old
        // ceil/floor ± MIN_GAP_BLOCKS bias landed the seam anywhere in [0.3, 1.3] while the
        // tracker's clean dead-band is only [MIN_GAP_BLOCKS, MAX_GAP_BLOCKS] — so most appended
        // groups started out of band and had to run a move-together pass before they could settle,
        // which is the opening leg of the collide → move-together → collide cycle. Spawning inside
        // the band means the usual group settles in one uninterrupted CLEAN_TICKS_FOR_SUCCESS run
        // with no shift at all, which is also the appender's per-lane spawn-rate floor.
        double desiredWorldX = preSeedDesiredX(idealX, forward);
        int initialPlaceX = (int) Math.round(desiredWorldX);
        int placeY = (int) Math.round(idealY);
        int placeZ = (int) Math.round(idealZ);

        CollisionAdjustResult adjusted = adjustForCollisions(
            initialPlaceX, placeY, placeZ, subLevelStride, dims, train, refTrainId, forward, newAnchor);
        int adjustedPlaceX = adjusted.placeX();

        // Drop the sub-block remainder if the collision pass moved the origin — that pass placed the
        // group deliberately, and a fractional nudge on top would work against its clearance.
        double preSeedRemainderX = (adjustedPlaceX == initialPlaceX)
            ? (desiredWorldX - initialPlaceX)
            : 0.0;

        BlockPos origin = new BlockPos(adjustedPlaceX, placeY, placeZ);
        double effectivePlaceX = adjustedPlaceX + preSeedRemainderX;
        double gap = forward ? (effectivePlaceX - idealX) : (idealX - effectivePlaceX);

        return new Plan(
            origin,
            subLevelStride,
            dims.height(),
            dims.width(),
            idealX,
            gap,
            forward,
            adjustedPlaceX - initialPlaceX,
            refAnchor,
            refWorldOriginVec.x,
            subLevelDelta,
            initialPlaceX,
            adjustedPlaceX,
            adjusted.lastOffenderPIdx(),
            adjusted.lastOffenderRegistryOnly(),
            preSeedRemainderX);
    }

    /**
     * World X a newly appended group's origin should land on so its seam against the reference
     * measures exactly {@link #TARGET_GAP_BLOCKS} — the centre of the placement tracker's clean
     * dead-band. {@code idealX} is the abutting (zero-gap) origin; forward spawns sit that gap
     * further along +X, backward spawns that gap further along −X.
     *
     * <p>Split out as a pure helper so the rounding/remainder split is unit-testable without a
     * level (mirrors {@link #placementTrackerShiftDx}).</p>
     */
    static double preSeedDesiredX(double idealX, boolean forward) {
        return forward ? (idealX + TARGET_GAP_BLOCKS) : (idealX - TARGET_GAP_BLOCKS);
    }

    private record Plan(
        BlockPos origin,
        int subLevelStride,
        int sizeY,
        int sizeZ,
        double idealX,
        double gap,
        boolean forward,
        int collisionAdjustments,
        // Diagnostic-only fields consumed by the [bwd-place] probe — they
        // record the inputs to the idealX placement math so a growing gap can
        // be attributed to the reference/anchor mismatch (H1) rather than the
        // settle dead-band (H2). Carry no behaviour.
        int refAnchor,
        double refShipToWorldX,
        int subLevelDelta,
        int initialPlaceX,
        int adjustedPlaceX,
        int lastOffenderPIdx,
        boolean lastOffenderRegistryOnly,
        // Sub-block world-X leftover from rounding the target-gap placement onto an integer
        // BlockPos origin. Applied once via preSeedGapShift so the seam lands on
        // TARGET_GAP_BLOCKS instead of a whole-block quantisation of it. Zero when the collision
        // pass moved the origin, and a no-op at zero.
        double preSeedRemainderX
    ) {}

    /**
     * Result of {@link #adjustForCollisions}: the resolved {@code placeX} plus
     * the last collision offender encountered (sentinel {@link Integer#MIN_VALUE}
     * for {@code lastOffenderPIdx} when the initial placement never overlapped a
     * sibling). {@code lastOffenderRegistryOnly} is {@code true} when that
     * offender was a culled/ghost carriage present in the spawn registry but
     * NOT in the visible train — the H3 diagnostic signal that a stale registry
     * AABB drove the shift.
     */
    private record CollisionAdjustResult(
        int placeX,
        int lastOffenderPIdx,
        boolean lastOffenderRegistryOnly
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
    private static CollisionAdjustResult adjustForCollisions(
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
        // siblingsForLog entry: { shipId, pIdx, registryOnly(0|1) }. The third
        // slot flags a sibling present in the spawn registry but NOT in the
        // visible train (a culled/ghost carriage) — surfaced to the [bwd-place]
        // probe as the H3 signal when such a stale box becomes the collision
        // offender.
        List<long[]> siblingsForLog = new ArrayList<>();
        List<AABBdc> siblings = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Trains.Carriage other : train) {
            long id = other.ship().id();
            if (!seen.add(id)) continue;
            AABBdc aabb = other.ship().worldAABB();
            if (isZeroAabb(aabb)) continue;
            siblings.add(aabb);
            siblingsForLog.add(new long[] { id, other.provider().getPIdx(), 0L });
        }
        Map<Integer, ManagedShip> registry = Trains.knownGroups(trainId);
        for (Map.Entry<Integer, ManagedShip> e : registry.entrySet()) {
            ManagedShip ship = e.getValue();
            long id = ship.id();
            if (!seen.add(id)) continue;
            // Skip registry-only ships that have been culled (isRemoved): their
            // worldAABB is frozen at the stale cull-time pose. Shoving a new
            // spawn off such a box opens a permanent void. Resident in-flight
            // siblings (not yet in the visible list) are still honoured.
            if (!ship.isResident()) continue;
            AABBdc aabb = ship.worldAABB();
            if (isZeroAabb(aabb)) continue;
            siblings.add(aabb);
            siblingsForLog.add(new long[] { id, e.getKey(), 1L });
        }

        int lastOffenderPIdx = Integer.MIN_VALUE;
        boolean lastOffenderRegistryOnly = false;

        for (int iter = 0; iter < COLLISION_ADJUST_SAFETY_LIMIT; iter++) {
            double candMinX = placeX;
            double candMaxX = placeX + subLevelStride;
            double candMinY = placeY;
            double candMaxY = placeY + height;
            double candMinZ = placeZ;
            double candMaxZ = placeZ + width;

            AABBdc colliding = null;
            int collidingPIdx = 0;
            boolean collidingRegistryOnly = false;
            for (int i = 0; i < siblings.size(); i++) {
                AABBdc o = siblings.get(i);
                if (candMaxX > o.minX() && candMinX < o.maxX()
                    && candMaxY > o.minY() && candMinY < o.maxY()
                    && candMaxZ > o.minZ() && candMinZ < o.maxZ()) {
                    colliding = o;
                    collidingPIdx = (int) siblingsForLog.get(i)[1];
                    collidingRegistryOnly = siblingsForLog.get(i)[2] == 1L;
                    break;
                }
            }
            if (colliding == null) {
                if (iter > 0) {
                    LOGGER.info("[DungeonTrain] Pre-spawn collision adjust resolved for newAnchor={} after {} iter(s); finalPlaceX={}",
                        newAnchor, iter, placeX);
                }
                return new CollisionAdjustResult(placeX, lastOffenderPIdx, lastOffenderRegistryOnly);
            }
            lastOffenderPIdx = collidingPIdx;
            lastOffenderRegistryOnly = collidingRegistryOnly;

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
                return new CollisionAdjustResult(placeX, lastOffenderPIdx, lastOffenderRegistryOnly);
            }
            LOGGER.debug("[DungeonTrain] Pre-spawn collision adjust iter={} newAnchor={}: shifted placeX {} → {} (offender pIdx={} offenderEdgeX={})",
                iter, newAnchor, placeX, newPlaceX, collidingPIdx,
                String.format("%.3f", forward ? colliding.maxX() : colliding.minX()));
            placeX = newPlaceX;
        }
        LOGGER.warn("[DungeonTrain] Pre-spawn collision adjust hit safety cap ({}) for newAnchor={} (forward={}); proceeding with placeX={}",
            COLLISION_ADJUST_SAFETY_LIMIT, newAnchor, forward, placeX);
        return new CollisionAdjustResult(placeX, lastOffenderPIdx, lastOffenderRegistryOnly);
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
                if (!ship.isResident()) continue; // ignore stale culled-ghost AABBs
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
     * The variant carriage {@code pIdx} rolls to — the "cart type" the F3+4 debug panel shows.
     * Uses the same pair {@link TrainAssembler} picks with at placement time, so the two agree.
     *
     * <p>Only called when a player actually crosses a carriage boundary, which is rare enough that
     * re-deriving it beats recording every placed variant. Never throws: a debug read-out is not
     * worth risking the train tick, so any failure degrades to an empty id and a blank line.</p>
     */
    /**
     * The carriage the player is standing in, resolved in the frame of the group that actually
     * holds them.
     *
     * <p>The train-wide {@code pIdx} computed above works entirely in the <b>lead</b> group's
     * frame: it projects the player through the lead ship's transform and divides by carriage
     * length. That assumes the train is one rigid body of evenly spaced carriages, but every group
     * is its own Sable sub-level, placed relative to the previous group's live position plus
     * {@code MIN_GAP} and a collision nudge. The gaps accumulate, so the further a player is from
     * the lead group the further that figure drifts from the carriage they are really in — which
     * is precisely the range where a debug read-out has to be trusted.</p>
     *
     * <p>So this finds the group whose own bounds contain the player (nearest, if they are in a
     * gap between groups) and indexes within that group's frame, off that group's own anchor pIdx.
     * Returns null when the train has no group to attribute them to.</p>
     */
    private static Integer occupiedPIdx(List<Trains.Carriage> train, ServerPlayer player,
                                        CarriageDims dims, int groupSize) {
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        Trains.Carriage best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Trains.Carriage c : train) {
            AABBdc aabb = c.ship().worldAABB();
            double dx = Math.max(0, Math.max(aabb.minX() - px, px - aabb.maxX()));
            double dy = Math.max(0, Math.max(aabb.minY() - py, py - aabb.maxY()));
            double dz = Math.max(0, Math.max(aabb.minZ() - pz, pz - aabb.maxZ()));
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = c;
                if (distSq == 0.0) break; // inside this group — no closer answer exists
            }
        }
        if (best == null) return null;

        int length = dims.length();
        int enclosedStartOffset = (groupSize > 1) ? CarriagePlacer.halfPadLen(dims) : 0;
        Vector3d local = new Vector3d(px, py, pz);
        best.ship().worldToShip(local);
        int slot = (int) Math.floor(
            (local.x - best.provider().getShipyardOrigin().getX() - enclosedStartOffset)
                / (double) length);
        return best.provider().getPIdx() + slot;
    }

    /**
     * What carriage {@code pIdx} was actually built as, for the F3+4 panel.
     *
     * <p>Read back from {@link PlacedCarriageFacts} rather than re-rolled. The pick is gated on the
     * group's world-X at the moment it was placed, and the train has moved since, so a recomputed
     * answer drifts further from the standing carriage the longer the run goes. An index this
     * session never placed reports empty ids — the panel shows a dash, which is the honest answer
     * rather than a confident wrong one.</p>
     */
    private static TrainDebugCarriagePacket debugCarriageAt(int pIdx) {
        PlacedCarriageFacts.Facts facts = PlacedCarriageFacts.get(pIdx);
        if (facts == null) {
            return new TrainDebugCarriagePacket(true, pIdx, "", "", "");
        }
        return new TrainDebugCarriagePacket(
            true, pIdx, facts.variantId(), facts.contentsId(), facts.subVariantId());
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
                DungeonTrainNet.sendTo(player, TrainDebugCarriagePacket.absent());
            }
            it.remove();
            LAST_SENT_DEBUG_PIDX.remove(uuid);
        }
    }
}
