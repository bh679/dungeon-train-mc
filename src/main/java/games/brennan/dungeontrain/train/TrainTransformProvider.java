package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.ship.InertiaSnapshot;
import games.brennan.dungeontrain.ship.KinematicDriver;
import games.brennan.dungeontrain.track.TrackGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Deque;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Per-carriage kinematic driver for a Sable sub-level holding exactly one
 * Dungeon Train carriage. Drives the sub-level at a fixed world-space velocity
 * by prescribing its next transform every server tick.
 *
 * <p>Trains are now collections of single-carriage sub-levels that share a
 * {@link #trainId}. Aggregation across the carriages of a train (lead /
 * tail, AABB union, kill-ahead targeting, etc.) lives in {@link Trains}.</p>
 *
 * <p>Entity ride-along still works: Sable's
 * {@link games.brennan.dungeontrain.ship.sable.SableManagedShip#applyTickOutput}
 * teleports the body and resets velocity each tick from {@link #nextTransform}.</p>
 *
 * <p>This class is also the Dungeon Train marker — a loaded sub-level whose
 * driver is a {@code TrainTransformProvider} is one of our carriages.</p>
 */
public final class TrainTransformProvider implements KinematicDriver {

    private static final Logger LOGGER = LogUtils.getLogger();
    // Diagnostic logger for residual jitter probes. Elevated to DEBUG in
    // DungeonTrain's constructor so these lines survive Forge's default
    // INFO root level. The per-carriage architecture removed APPENDER-driven
    // COM drift; mutation-driven drift (explosions, mining, portal mirroring)
    // is still real and is corrected by CarriagePivotPin, so these probes —
    // and its [pinCorrected] line — remain the live regression signal.
    private static final Logger JITTER_LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");

    // SableKinematicTicker fires nextTransform every server tick (20 Hz),
    // so each call represents 1/20 s of game time. canonicalPos must advance
    // by velocity * 1/20 per call so its motion matches what the rendered
    // body shows over a server-tick interval.
    private static final double PHYSICS_DT = 1.0 / 20.0;
    private static final Vector3dc ZERO_OMEGA = new Vector3d();

    // Jitter instrumentation constants.
    private static final int JITTER_DEBUG_PERIOD = 20;
    private static final double JITTER_COMDELTA_LOG_STEP = 0.1;
    private static final double JITTER_TRIPWIRE_MULTIPLIER = 2.0;
    private static final int JITTER_TRIPWIRE_COOLDOWN_TICKS = 10;
    // Sub-mm threshold for "the pivot moved" events so we don't log every
    // floating-point round-off. Real COM recalc would produce far larger
    // deltas; on Sable per-carriage we expect this to never fire.
    private static final double PIVOT_MOVE_EPSILON = 1e-6;

    /**
     * World-load motion grace: a freshly-(re)loaded train holds at its spawn
     * position for this many ticks before it begins to move. This suppresses a
     * one-time burst of Sable "Received a sub-level movement packet for a
     * non-existent sub-level" errors at the world-load/join instant.
     *
     * <p>Root cause: Sable sends a carriage's movement snapshot (over UDP) and
     * its full-sync ({@code ClientboundStartTrackingSubLevelPacket}, over TCP,
     * which is what actually registers the {@code ClientSubLevel} client-side)
     * in the SAME tracking tick. The UDP snapshot can be processed on the
     * client before the TCP full-sync lands, so the sub-level doesn't exist yet
     * and Sable logs an error — once per carriage until the full-sync arrives.
     * DT makes this fire because the carriages move every tick from tick 0.</p>
     *
     * <p>A stationary carriage emits NO movement snapshot at all (Sable's
     * {@code sendMovementUpdates} skips a sub-level whose pose is within
     * tolerance of its last-networked pose), so holding the whole train still
     * until the joining client has registered every sub-level closes the race
     * regardless of transport. ~1 s at 20 TPS — long enough to cover the
     * bootstrap eager-fill and the client's first packet-processing pass, short
     * enough to read as a natural standstill start. Tunable. See
     * {@link #beginLoadGrace} and {@link #MOTION_HOLD_UNTIL}.</p>
     */
    private static final long WORLD_LOAD_MOTION_GRACE_TICKS = 20L;

    /**
     * Per-dimension tick until which newly-spawned carriages hold at their
     * spawn position (see {@link #WORLD_LOAD_MOTION_GRACE_TICKS}). Keyed by
     * dimension so a fresh train spawned into any dimension — world-load
     * bootstrap or a respawn into a not-yet-visited dimension — gets its own
     * grace window. A carriage whose {@code spawnGameTick} is already past the
     * dimension's hold deadline (every carriage appended during normal play) is
     * unaffected: {@code max(spawnGameTick, holdUntil)} leaves its time origin
     * untouched, so appended carriages join the moving train in lockstep with
     * zero behaviour change. Static + process-lifetime like
     * {@link games.brennan.dungeontrain.ship.sable.SableManagedShip}'s driver
     * map; re-seeded on each server start (fresh process ⇒ fresh map).
     */
    private static final java.util.Map<ResourceKey<Level>, Long> MOTION_HOLD_UNTIL =
        new ConcurrentHashMap<>();

    /**
     * Open a world-load motion-grace window for {@code level} starting at
     * {@code currentGameTick}. Called once when a fresh train is spawned into a
     * dimension (see {@code TrainBootstrapEvents.ensureTrainSpawned}), before
     * any carriage's first kinematic tick — so the seed group, the bootstrap
     * eager-fill, and (on the respawn path) the per-tick appender's first
     * groups all share one hold deadline and start moving together.
     */
    public static void beginLoadGrace(ResourceKey<Level> level, long currentGameTick) {
        MOTION_HOLD_UNTIL.put(level, currentGameTick + WORLD_LOAD_MOTION_GRACE_TICKS);
    }

    /**
     * Elapsed ticks since a carriage's effective motion origin, clamped so a
     * held carriage never advances (or runs backward). The effective origin is
     * {@code max(spawnGameTick, holdUntilTick)}: during a level's world-load
     * grace window a freshly-spawned carriage ({@code spawnGameTick <=
     * holdUntilTick}) is pinned to {@code holdUntilTick} and reports 0 elapsed
     * ticks until that deadline; every carriage appended later
     * ({@code spawnGameTick > holdUntilTick}) is unaffected.
     *
     * <p>Taking {@code max()} BEFORE the subtraction is deliberate: it keeps
     * the "no grace" sentinel ({@link Long#MIN_VALUE} for a dimension that was
     * never granted a window) from underflowing {@code currentGameTick -
     * holdUntilTick}. Pure/static so it unit-tests without a Minecraft
     * bootstrap.</p>
     */
    static long effectiveElapsedTicks(long currentGameTick, long spawnGameTick, long holdUntilTick) {
        long effectiveOrigin = Math.max(spawnGameTick, holdUntilTick);
        return Math.max(0L, currentGameTick - effectiveOrigin);
    }

    /**
     * As {@link #effectiveElapsedTicks}, less the ticks this train has spent standing still.
     *
     * <p><b>Why the subtraction goes here and nowhere else.</b> This one term is the only place a
     * carriage's position can be stopped. The formula below re-derives the position from scratch every
     * tick, so there is no step to skip; and zeroing the velocity would re-evaluate the whole history
     * of the formula and yank the carriage back to where it spawned. Take ticks out of the count and
     * the train stands still; put no more in and it carries on from exactly where it stopped, with no
     * jump at either edge. See {@link TrainMotionFreeze}.</p>
     *
     * <p><b>{@code frozenSinceSpawn}, not {@code frozenTotal}.</b> A carriage appended after an
     * earlier freeze was not there for it, and subtracting time it never experienced would place it
     * behind the siblings it is supposed to couple to. Each provider captures the running total when
     * it captures its spawn tick and passes the difference.</p>
     *
     * <p>Clamped at zero, like the hold above, so a counter that somehow ran ahead of the elapsed
     * ticks can only stop a carriage, never reverse it.</p>
     */
    static long effectiveElapsedTicks(long currentGameTick, long spawnGameTick, long holdUntilTick,
                                      long frozenSinceSpawn) {
        long elapsed = effectiveElapsedTicks(currentGameTick, spawnGameTick, holdUntilTick);
        return Math.max(0L, elapsed - Math.max(0L, frozenSinceSpawn));
    }

    private volatile Vector3d targetVelocity;
    private final BlockPos shipyardOrigin;
    private final ResourceKey<Level> dimensionKey;
    /**
     * Carriage footprint snapshot captured at spawn time. Immutable for the
     * sub-level's lifetime — matches the "spawn-time-captured, read-only
     * afterward" pattern used by {@link #shipyardOrigin} and
     * {@link #trackGeometry}.
     */
    private final CarriageDims dims;

    /**
     * Anchor (lowest-index) carriage's pIdx in this sub-level's group.
     * The group spans pIdx range {@code [pIdx, pIdx + groupSize - 1]}.
     * Stable for the sub-level's lifetime. Variant selection
     * (deterministic per-index) iterates through the range.
     */
    private final int pIdx;

    /**
     * Number of carriages bundled into this sub-level. Captured at spawn
     * time from {@link CarriageGenerationConfig#groupSize()} (default 3,
     * range 1-16). Constant for this sub-level's lifetime; runtime config
     * changes only affect freshly-spawned trains.
     */
    private final int groupSize;

    /**
     * UUID shared by every carriage sub-level belonging to the same train.
     * Used by {@link Trains} to group sub-levels back into trains for
     * aggregations (lead/tail dispatch, AABB union, runway clearing).
     */
    private final UUID trainId;

    // Lazily captured on the first tick so the sub-level's spawn-time
    // orientation, world position, and model-space pivot become the
    // authoritative baseline. Re-applying them every tick makes the
    // carriage immune to gravity, collision impulses, and COM-shift side
    // effects from block changes.
    //
    // {@code spawnWorldPos} and {@code spawnGameTick} are the basis for
    // a deterministic per-tick position calculation:
    // {@code canonicalPos = spawnWorldPos + targetVelocity * (currentGameTick − spawnGameTick) * PHYSICS_DT}.
    // This formula is resilient to chunk unload/reload — when a sub-level's
    // chunks are evicted (player flies away), the provider's
    // {@link #nextTransform} doesn't fire, but the missed ticks don't
    // accumulate as drift because each surviving call recomputes
    // {@code canonicalPos} from scratch. Without this, the previous
    // implementation incremented {@code canonicalPos} per tick and
    // sub-levels that were unloaded for N ticks resumed N*velocity*dt
    // blocks behind their always-loaded siblings (visible as half-carriage
    // overlaps and gaps when the player flies back to a previously-visited
    // section of the train).
    private Quaterniondc lockedRotation;
    private Vector3d canonicalPos;
    private Vector3dc lockedPositionInModel;
    private Vector3d spawnWorldPos;
    private long spawnGameTick = -1L;

    /**
     * This train's running frozen-tick total at the moment {@link #spawnGameTick} was captured.
     *
     * <p>The elapsed term subtracts {@code frozenTicks(trainId) − this}, never the total: a carriage
     * appended after an earlier freeze was not there for it, and subtracting time it never
     * experienced would place it behind the siblings it couples to. <b>Re-based wherever
     * {@code spawnGameTick} is</b> — including the re-anchor after a tick gap, where keeping the old
     * value would subtract every historical frozen tick a second time from an elapsed count that now
     * starts at the re-anchor. See {@link TrainMotionFreeze}.</p>
     */
    private long frozenTicksAtSpawn = 0L;

    // One-shot sub-block world-X nudge applied the instant spawnWorldPos is
    // captured on the first kinematic tick (see nextTransform). Lets the
    // bootstrap eager-fill inject a fractional inter-group gap that integer
    // BlockPos spawn origins cannot express: the blocks land on an integer
    // grid, this carries the leftover fraction so the visible seam settles at
    // exactly the target gap. shiftSpawnPosition can't do this at spawn time —
    // it no-ops while spawnWorldPos is still null. Consumed (reset to 0) on
    // application so it fires once. See {@link #preSeedSpawnShiftX}.
    private double pendingSpawnShiftX = 0.0;

    // Game tick spawnWorldPos was captured on and any pendingSpawnShiftX applied — i.e. the tick
    // this carriage's pose last moved for a reason that is NOT a placement-tracker shift.
    //
    // The tracker measures seams from the body's worldAABB, which lags the pose by a tick or two.
    // The pre-seed is a move of up to half a block, so a reading taken inside that lag describes
    // where the group WAS, at its quantised integer origin, not where it now is. Every other shift
    // in that system arms the SHIFT_SETTLE_TICKS throttle before the next reading; this one had
    // nothing to arm it with, which is exactly why freshly spawned seams read too-far and then
    // too-close (down to 0.00 — touching) and burned two corrections undoing each other.
    // -1 until captured. See TrainCarriageAppender.placementReadingIsTrustworthy.
    private volatile long spawnCaptureGameTick = -1L;

    // When true, {@link TrainCarriageAppender} skips this carriage's train
    // entirely — no append regardless of player pIdx. Set by debug probes
    // (see {@code /dt debug pair}) so test fixtures stay exactly the size
    // they were assembled at; production carriages leave this false.
    private volatile boolean appenderDisabled = false;

    // Jitter-probe state. Owned by the physics tick caller — no volatile
    // needed; never read from another thread.
    private long physicsTickCounter;
    private Vector3d prevEffectivePos;
    // gameTime of the previous {@link #nextTransform} call (-1 before the
    // first call). Used to detect a resume-after-cull: when Sable culls this
    // carriage's sub-level to holding it drops out of the kinematic ticker,
    // so on reload the next call sees gameTime advanced by many ticks. That
    // gap makes the deterministic formula catch up in a single frame — the
    // correct (sibling-aligned) position, but emitted as one large teleport.
    // See {@link #shouldReanchor} and the re-anchor block in nextTransform.
    private long lastNextTransformGameTick = -1L;
    private double lastLoggedComDeltaX = Double.NaN;
    private int tripwireCooldown;
    private double lastPivotX = Double.NaN;
    private double lastPivotY = Double.NaN;
    private double lastPivotZ = Double.NaN;
    // One-shot guard so the [panic.canonicalPos] line fires once per
    // sub-level spawn — otherwise a sustained NaN would spam every tick.
    private boolean canonicalPosNanLogged;
    // Stamped by {@link #onExternalMassChange} when CarriagePivotPin corrects a
    // pivot that a block change had moved — so the jitter probe's
    // {@code mutationDriven} flag means "the last pivot movement was a mass
    // recompute we just undid", not merely "a block changed somewhere".
    private long lastMutationTick = -1L;
    private volatile long lastMutationNanos = 0L;

    /**
     * Inertia snapshot used by {@link ShipyardShifter} on VS to undo
     * COM-recalc side effects. No-op on Sable (mass tracker is read-only)
     * but the field stays so the shifter compiles unchanged across physics
     * mods.
     */
    private volatile InertiaSnapshot lockedInertia;

    /**
     * World-space track layout under this carriage's train. Set by
     * {@link TrainAssembler}; the same geometry is set on every
     * carriage's provider in a train (it's shared train-wide). Used by
     * {@link games.brennan.dungeontrain.track.TrackGenerator} on the
     * train's "tail" carriage to drive bed/track painting.
     */
    private volatile TrackGeometry trackGeometry;

    /**
     * Chunk tracking for the track-painter / tunnel-painter. These are
     * train-wide concerns, but to avoid a separate registry in B.1 we
     * host them on each carriage's provider — only the train's tail (per
     * {@link Trains#tail(java.util.List)}) is consulted by per-tick gen
     * passes. When the tail changes, the new tail starts with empty queues
     * and re-discovers chunks; painting is idempotent so the cost is just
     * one extra scan when extending the train backward.
     */
    private final Set<Long> filledChunks = ConcurrentHashMap.newKeySet();
    private final Deque<Long> pendingChunks = new ConcurrentLinkedDeque<>();

    public TrainTransformProvider(
        Vector3dc targetVelocity,
        BlockPos shipyardOrigin,
        ResourceKey<Level> dimensionKey,
        int pIdx,
        int groupSize,
        CarriageDims dims,
        UUID trainId
    ) {
        if (groupSize < 1) {
            throw new IllegalArgumentException("groupSize must be ≥ 1, got " + groupSize);
        }
        this.targetVelocity = new Vector3d(targetVelocity);
        this.shipyardOrigin = shipyardOrigin.immutable();
        this.dimensionKey = dimensionKey;
        this.dims = dims;
        this.pIdx = pIdx;
        this.groupSize = groupSize;
        this.trainId = trainId;
    }

    /** Anchor (lowest) carriage pIdx of this group. */
    public int getPIdx() {
        return pIdx;
    }

    /** Number of carriages in this group / sub-level (≥ 1). */
    public int getGroupSize() {
        return groupSize;
    }

    /** Highest carriage pIdx of this group: {@code pIdx + groupSize - 1}. */
    public int getGroupHighestPIdx() {
        return pIdx + groupSize - 1;
    }

    /** True iff {@code carriagePIdx} is one of this group's carriages. */
    public boolean containsPIdx(int carriagePIdx) {
        return carriagePIdx >= pIdx && carriagePIdx <= getGroupHighestPIdx();
    }

    public UUID getTrainId() {
        return trainId;
    }

    public Vector3dc getTargetVelocity() {
        return targetVelocity;
    }

    /**
     * Replace the target velocity with a fresh vector. Safe to call from any
     * thread — the physics tick reads {@code targetVelocity} via the volatile
     * reference and never mutates it in place.
     */
    public void setTargetVelocity(Vector3dc v) {
        this.targetVelocity = new Vector3d(v);
    }

    public BlockPos getShipyardOrigin() {
        return shipyardOrigin;
    }

    /**
     * Pre-seed {@link #spawnGameTick} to the ACTUAL game tick when this
     * carriage was assembled — not the (potentially much later) tick when
     * Sable first calls {@link #nextTransform}.
     *
     * <p>Why: the deterministic position formula
     * {@code canonicalPos = spawnWorldPos + velocity * (currentTick - spawnGameTick) * PHYSICS_DT}
     * uses {@code spawnGameTick} as its time origin. If we let
     * {@code nextTransform}'s lazy init capture {@code spawnGameTick} on
     * the FIRST kinematic call, far-from-player carriages whose first
     * tick is delayed by N game ticks would have their time origin set N
     * ticks late, leaving them permanently behind the rest of the train
     * by {@code velocity * N} blocks — the field-observed eager-fill
     * overlap pattern.</p>
     *
     * <p>{@link #spawnWorldPos} and {@link #canonicalPos} are STILL
     * captured lazily from {@code input.currentPosition()} on first
     * kinematic tick: Sable uses the block-AABB centre as the pose
     * translation reference (see {@code SableShipyard.computeAnchor}), and
     * synthesising that centre at spawn time is fragile across carriage
     * variants. By only pre-seeding the tick, the position reference
     * stays in Sable's native coordinate frame and the formula simply
     * extrapolates forward from the correct centre by the correct amount
     * of elapsed time.</p>
     *
     * <p>Idempotent: only writes when {@code spawnGameTick == -1}.</p>
     */
    public void preSeedSpawnTick(long currentGameTick) {
        if (this.spawnGameTick != -1L) return;
        this.spawnGameTick = currentGameTick;
        this.frozenTicksAtSpawn = TrainMotionFreeze.frozenTicks(trainId);
    }

    /**
     * Pre-seed a sub-block world-X offset applied once, the instant
     * {@code spawnWorldPos} is captured on the first kinematic tick. The
     * bootstrap eager-fill uses this to realise a fractional inter-group gap:
     * the group's blocks are placed at an integer {@code BlockPos} origin
     * (nearest whole block), and {@code dx} carries the leftover fraction
     * ({@code desiredWorldX − round(desiredWorldX)}, so in {@code [-0.5, 0.5]})
     * so the visible seam ends up at exactly the target gap instead of being
     * quantised to a whole block.
     *
     * <p>Applied unconditionally at capture — independent of
     * {@code placedSuccessfully} — so it survives the eager-fill placement
     * exemption from the per-tick collision tracker. Additive, so multiple
     * pre-seeds before the first tick accumulate.</p>
     */
    public void preSeedSpawnShiftX(double dx) {
        this.pendingSpawnShiftX += dx;
    }

    public ResourceKey<Level> getDimensionKey() {
        return dimensionKey;
    }

    public CarriageDims dims() {
        return dims;
    }

    public boolean isAppenderDisabled() {
        return appenderDisabled;
    }

    public void setAppenderDisabled(boolean disabled) {
        this.appenderDisabled = disabled;
    }

    public long getLastMutationTick() {
        return lastMutationTick;
    }

    public void setLastMutationTick(long tick) {
        this.lastMutationTick = tick;
    }

    public long getLastMutationNanos() {
        return lastMutationNanos;
    }

    public void setLastMutationNanos(long nanos) {
        this.lastMutationNanos = nanos;
    }

    public long getPhysicsTickCounter() {
        return physicsTickCounter;
    }

    public boolean isCanonicalPosFinite() {
        Vector3d p = canonicalPos;
        if (p == null) return true;
        return Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z);
    }

    public Vector3dc getCanonicalPos() {
        return canonicalPos;
    }

    public Vector3dc getLockedPositionInModel() {
        return lockedPositionInModel;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The spawn-locked pivot captured on the first kinematic tick. {@code null} until
     * then, which is exactly the window in which there is nothing to pin to.</p>
     */
    @Override
    public Vector3dc lockedPositionInModel() {
        return lockedPositionInModel;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Feeds the {@code [pivotMoved]} probe's {@code mutationDriven} flag. Reached only
     * when {@code CarriagePivotPin} found real drift and corrected it, so a run of these
     * marks genuine mass changes (an explosion, a player mining a carriage block) rather
     * than the thousands of block writes track and tunnel painting push through the same
     * choke point every tick.</p>
     */
    @Override
    public void onExternalMassChange() {
        this.lastMutationTick = physicsTickCounter;
        this.lastMutationNanos = System.nanoTime();
    }

    /**
     * The {@code level.getGameTime()} value captured on this driver's
     * first {@link #nextTransform} call — i.e. the first physics tick
     * after assembly. Returns {@code -1L} until that first tick fires.
     * Used by diagnostic overlays (collision-warning chat) to report
     * "ticks since spawn" without having to plumb game-time into
     * separate tracking maps.
     */
    public long getSpawnGameTick() {
        return spawnGameTick;
    }

    // ──────────────────────────────────────────────────────────────────
    // Placement state machine (per-carriage). Owned by the per-tick
    // collision tracker in TrainCarriageAppender — once a carriage runs
    // {@code TrainCarriageAppender#CLEAN_TICKS_FOR_SUCCESS} consecutive
    // ticks without an AABB overlap against any sibling, it transitions
    // into {@code placedSuccessfully = true} and is permanently exempt
    // from further checks (overlay disappears, no more shifts).
    //
    // While {@code placedSuccessfully = false}: a colliding tick
    // shifts the carriage forward via {@link #shiftSpawnPosition} and
    // resets {@code consecutiveCleanTicks} to 0.
    private volatile boolean placedSuccessfully = false;
    private volatile int consecutiveCleanTicks = 0;

    // Collide-then-move-together-then-collide lock. Tracks whether the
    // placement tracker's "move together" (close-gap) branch should be
    // permanently disabled for this carriage to stop the move-together
    // and collision-pushback systems from fighting on the same group.
    //
    // State transitions (driven by TrainCarriageAppender.runPlacementCollisionTracker):
    //   hasCollidedDuringPlacement: false → true on first collision tick.
    //   hasRunMoveTogetherAfterCollision: false → true when a move-together
    //       shift fires while hasCollidedDuringPlacement is already true.
    //   moveTogetherLocked: false → true when a collision is detected while
    //       hasRunMoveTogetherAfterCollision is already true — the
    //       collide → move-together → collide cycle is observed and any
    //       further move-together for this carriage is suppressed.
    // All three are one-way. Collision pushback is never gated by the lock.
    private volatile boolean hasCollidedDuringPlacement = false;
    private volatile boolean hasRunMoveTogetherAfterCollision = false;
    private volatile boolean moveTogetherLocked = false;

    /**
     * Pending contents-entity spawn records, one per enclosed carriage in
     * this group. Stashed by {@code TrainAssembler.spawnGroup} after the
     * blocks-only contents pass; fired by the appender's placement-collision
     * tracker once this carriage transitions to {@code placedSuccessfully}.
     *
     * <p>Why deferred: the placement tracker can shift {@code spawnWorldPos}
     * every tick for up to {@code CLEAN_TICKS_FOR_SUCCESS} ticks while
     * resolving collisions. Spawning entities AFTER that period guarantees
     * the carriage's shipyard chunks are stable when entities are attached,
     * sidestepping any timing race between VS's shipyard-entity mixin and
     * the per-tick shifts.</p>
     *
     * <p>Volatile to match the existing placement-state field-visibility
     * pattern — write from the assembly thread, read from the appender's
     * server-tick handler.</p>
     */
    private volatile PendingContentsEntitySpawn[] pendingContentsEntitySpawns = null;
    /**
     * {@code true} iff this carriage was spawned BEHIND the existing
     * train (newAnchor &lt; existing trainMinAnchor). Read by the
     * collision-check box positioner so the 1×3×5 detection slab sits
     * at the end of the carriage that faces the train — for forward
     * spawns that's the LOW-X corner, for backward spawns the HIGH-X
     * corner. Default {@code false} (forward) — set by the appender's
     * spawn loop right after assembly.
     */
    private volatile boolean spawnedBackward = false;

    /**
     * Consecutive server ticks this group's anchor has been beyond
     * {@code ContentsDespawnController.DESPAWN_RADIUS_BLOCKS} from every
     * player. Reset to 0 the moment any player comes back inside that
     * radius, so the despawn sweep only fires for a group that has been
     * genuinely abandoned rather than one a player is pacing past.
     *
     * <p>Counterpart to Sable's {@code DtFreezable} inactive-tick counter,
     * kept here instead because the despawn gate is keyed on the DT group
     * (one provider) rather than the sub-level.</p>
     */
    private volatile int contentsAwayTicks = 0;

    /**
     * {@code true} while this group's contents mobs are snapshotted to disk
     * rather than live in the level. Together with
     * {@link #hasPendingContentsEntitySpawns()} this gives the group's
     * three-state lifecycle: PENDING (pending array non-null) → LIVE (both
     * false) ⇄ SNAPSHOT (this true). The spawn gate owns the first edge,
     * {@code ContentsDespawnController} owns the last pair, and neither
     * acts on a group in the other's state.
     */
    private volatile boolean contentsDespawned = false;

    /**
     * Entity count in the current snapshot — diagnostics only, surfaced by
     * the {@code [despawn]} log line and the debug status command so a
     * held snapshot is visible without reading the file. Zero whenever
     * {@link #isContentsDespawned()} is false.
     */
    private volatile int contentsSnapshotSize = 0;

    public int getContentsAwayTicks() {
        return contentsAwayTicks;
    }

    public void setContentsAwayTicks(int ticks) {
        this.contentsAwayTicks = ticks;
    }

    public boolean isContentsDespawned() {
        return contentsDespawned;
    }

    public void setContentsDespawned(boolean despawned) {
        this.contentsDespawned = despawned;
    }

    public int getContentsSnapshotSize() {
        return contentsSnapshotSize;
    }

    public void setContentsSnapshotSize(int size) {
        this.contentsSnapshotSize = size;
    }

    /**
     * Consecutive ticks a contents restore has been deferred for this group. A restore is deferred
     * while the carriage is soft-frozen, because a frozen sub-level stops advancing its pose and the
     * ship→world conversion would place mobs where the carriage used to be. Normally clears within a
     * tick or two; a sustained non-zero value is the canary that the freeze and despawn gates
     * disagree about what counts as near.
     */
    private volatile int contentsRestoreDeferredTicks = 0;

    public int getContentsRestoreDeferredTicks() {
        return contentsRestoreDeferredTicks;
    }

    public void setContentsRestoreDeferredTicks(int ticks) {
        this.contentsRestoreDeferredTicks = ticks;
    }

    public boolean isPlacedSuccessfully() {
        return placedSuccessfully;
    }

    public void markPlacedSuccessfully() {
        this.placedSuccessfully = true;
    }

    /**
     * Stash the per-carriage pending entity-spawn records captured by
     * {@code TrainAssembler.spawnGroup}. Each slot in the array corresponds
     * to one enclosed carriage in this group. A {@code null} entry means
     * that slot was a FLATBED (no contents) and never needs an entity
     * spawn pass.
     *
     * <p>The array reference is held volatile so the appender's tick
     * handler observes the post-assembly write without any extra
     * synchronisation.</p>
     */
    public void setPendingContentsEntitySpawns(PendingContentsEntitySpawn[] pending) {
        this.pendingContentsEntitySpawns = pending;
    }

    /**
     * Atomically take the pending entity spawns, returning the previously
     * stashed array (or {@code null} if already taken / never set). Used by
     * the appender so the {@code markPlacedSuccessfully()} branch fires the
     * spawns exactly once — a second tick that re-enters the branch (which
     * shouldn't happen since {@code placedSuccessfully} flips one-way, but
     * defensive) sees {@code null} and skips.
     */
    /**
     * Leased (relay) carriages' entity spawns, stashed and fired at exactly the same settle point as
     * {@link #pendingContentsEntitySpawns}. Separate from that array on purpose — see
     * {@link PendingRelayEntitySpawn}'s javadoc.
     */
    private volatile PendingRelayEntitySpawn[] pendingRelayEntitySpawns = null;

    public void setPendingRelayEntitySpawns(PendingRelayEntitySpawn[] pending) {
        this.pendingRelayEntitySpawns = pending;
    }

    /** Atomically take the pending relay entity spawns, so the settle branch fires them exactly once. */
    public PendingRelayEntitySpawn[] takePendingRelayEntitySpawns() {
        PendingRelayEntitySpawn[] snapshot = pendingRelayEntitySpawns;
        pendingRelayEntitySpawns = null;
        return snapshot;
    }

    public PendingContentsEntitySpawn[] takePendingContentsEntitySpawns() {
        PendingContentsEntitySpawn[] snapshot = pendingContentsEntitySpawns;
        pendingContentsEntitySpawns = null;
        return snapshot;
    }

    /**
     * Non-mutating peek used by the per-tick player-distance gate to skip
     * carriages that already fired (or never had any contents). Read of the
     * volatile field returns the same value the next {@link #takePendingContentsEntitySpawns}
     * call would consume — true iff that consume would yield a non-null array.
     */
    public boolean hasPendingContentsEntitySpawns() {
        return pendingContentsEntitySpawns != null;
    }

    public int getConsecutiveCleanTicks() {
        return consecutiveCleanTicks;
    }

    public void incrementConsecutiveCleanTicks() {
        this.consecutiveCleanTicks++;
    }

    public void resetConsecutiveCleanTicks() {
        this.consecutiveCleanTicks = 0;
    }

    public boolean isSpawnedBackward() {
        return spawnedBackward;
    }

    public void setSpawnedBackward(boolean v) {
        this.spawnedBackward = v;
    }

    public boolean hasCollidedDuringPlacement() {
        return hasCollidedDuringPlacement;
    }

    public void markCollidedDuringPlacement() {
        this.hasCollidedDuringPlacement = true;
    }

    public boolean hasRunMoveTogetherAfterCollision() {
        return hasRunMoveTogetherAfterCollision;
    }

    public void markRunMoveTogetherAfterCollision() {
        this.hasRunMoveTogetherAfterCollision = true;
    }

    public boolean isMoveTogetherLocked() {
        return moveTogetherLocked;
    }

    public void markMoveTogetherLocked() {
        this.moveTogetherLocked = true;
    }

    /**
     * Add {@code (dx, dy, dz)} world-space blocks to {@link #spawnWorldPos}.
     * The deterministic position formula in {@link #nextTransform} reads
     * spawnWorldPos every tick, so the next physics tick teleports the
     * carriage to the new offset position automatically — no extra
     * apply-output plumbing required.
     *
     * <p>No-op until {@code spawnWorldPos} is captured on the first
     * kinematic tick. Used by the collision-resolution loop to nudge a
     * carriage out of overlap with a sibling.</p>
     */
    public void shiftSpawnPosition(double dx, double dy, double dz) {
        if (spawnWorldPos == null) return;
        spawnWorldPos.add(dx, dy, dz);
    }

    /**
     * Whether {@code spawnWorldPos} has been captured on the first kinematic
     * tick — i.e. whether {@link #shiftSpawnPosition} does anything yet.
     *
     * <p>Read by {@code TrainCarriageAppender.applyPlacementShift} so a
     * catch-up burst propagates only a shift the leader actually applied:
     * leader and followers must move together or not at all, otherwise the
     * intra-burst seam is exactly what absorbs the difference.</p>
     */
    /**
     * World distance a train travels along one axis in {@code ticks} at {@code velocity} blocks per
     * second — the same {@code velocity * ticks * PHYSICS_DT} the canonical-position formula uses.
     *
     * <p>Exposed because a catch-up fill run plans a group against the placement of the group
     * before it, which was computed on an earlier tick: the train has moved since, and at
     * speed 12 that is 0.6 blocks per tick — wider than the seam itself.</p>
     */
    public static double travelDistance(double velocity, long ticks) {
        return velocity * ticks * PHYSICS_DT;
    }

    public boolean hasCapturedSpawnPosition() {
        return spawnWorldPos != null;
    }

    /**
     * Game tick this carriage's spawn position was captured and its pre-seeded sub-block nudge
     * applied, or {@code -1} while it has never kinematically ticked. Read by the placement
     * tracker to tell a seam reading that reflects the current pose from one taken while the
     * body's AABB still describes the pre-nudge placement.
     */
    public long getSpawnCaptureGameTick() {
        return spawnCaptureGameTick;
    }

    /**
     * Shift world X by {@code dx} now if the spawn position has been captured,
     * otherwise bank it via {@link #preSeedSpawnShiftX} so it lands the instant
     * it is. Used to move a catch-up burst's follower in lockstep with its
     * leader even in the first ticks after spawn, when Sable may have given the
     * two sub-levels their first kinematic tick on different ticks.
     */
    public void shiftOrDeferSpawnShiftX(double dx) {
        if (spawnWorldPos == null) preSeedSpawnShiftX(dx);
        else spawnWorldPos.add(dx, 0.0, 0.0);
    }

    /**
     * Advance the sub-level's reference frame forward by {@code shipyardDelta}.
     * Used by {@link ShipyardShifter} on VS to keep the pivot close to the
     * player's current shipyard position. No-op-equivalent on Sable (no
     * shipyard wall), but the math still preserves invariance:
     * {@code voxel_world = canonicalPos + R·(voxel_shipyard − pivot)}; setting
     * {@code pivot += Δ_shipyard} and {@code canonicalPos += R·Δ} leaves every
     * voxel_world unchanged.
     */
    public void shiftReference(Vector3dc shipyardDelta) {
        if (lockedPositionInModel == null || canonicalPos == null) return;
        Vector3d worldDelta = new Vector3d(shipyardDelta);
        if (lockedRotation != null) lockedRotation.transform(worldDelta);
        canonicalPos.add(worldDelta);
        lockedPositionInModel = new Vector3d(lockedPositionInModel).add(shipyardDelta);
    }

    public InertiaSnapshot getLockedInertia() {
        return lockedInertia;
    }

    public void setLockedInertia(InertiaSnapshot locked) {
        this.lockedInertia = locked;
    }

    public TrackGeometry getTrackGeometry() {
        return trackGeometry;
    }

    public void setTrackGeometry(TrackGeometry geometry) {
        this.trackGeometry = geometry;
    }

    public Set<Long> getFilledChunks() {
        return filledChunks;
    }

    public Deque<Long> getPendingChunks() {
        return pendingChunks;
    }

    /**
     * Pure-kinematic tick output. Position = {@link #canonicalPos}, rotation
     * = {@link #lockedRotation}, pivot = {@link #lockedPositionInModel}.
     * Returns {@code null} until the spawn baseline has been captured on
     * the first physics tick.
     */
    public TickOutput computeCompensatedTransform(TickInput current) {
        if (lockedRotation == null) return null;
        return new TickOutput(
            canonicalPos,
            lockedRotation,
            lockedPositionInModel,
            targetVelocity,
            ZERO_OMEGA);
    }

    /**
     * Pure-logic helper for the pivot-drift compensation math. Extracted so
     * unit tests can exercise the arithmetic without mocking
     * {@link KinematicDriver.TickInput} / {@link KinematicDriver.TickOutput}.
     *
     * Formula: {@code effectivePos = canonicalPos + lockedRotation · (currentPivot − lockedPivot)}.
     */
    static Vector3d computeEffectivePosition(
        Vector3dc canonicalPos,
        Vector3dc currentPivot,
        Vector3dc lockedPivot,
        Quaterniondc lockedRotation
    ) {
        Vector3d comDelta = new Vector3d(currentPivot).sub(lockedPivot);
        lockedRotation.transform(comDelta);
        return new Vector3d(canonicalPos).add(comDelta);
    }

    /**
     * True iff {@code nowGameTick} follows {@code lastGameTick} with a gap of
     * more than one tick — i.e. this driver skipped at least one physics tick
     * (its sub-level was culled to holding and has just reloaded). Extracted as
     * a pure helper so the re-anchor decision is unit-testable without a
     * Minecraft/Sable bootstrap, mirroring {@link #computeEffectivePosition}.
     *
     * <p>{@code lastGameTick == -1} (before the first call) never re-anchors:
     * the first call establishes the spawn baseline, it is not a resume.</p>
     */
    static boolean shouldReanchor(long lastGameTick, long nowGameTick) {
        return lastGameTick != -1L && (nowGameTick - lastGameTick) > 1L;
    }

    @Override
    public TickOutput nextTransform(TickInput input) {
        long currentGameTick = input.gameTime();
        if (lockedRotation == null) {
            // Force identity rotation — the carriage must always be axis-
            // aligned. Capturing input.currentRotation() here would freeze
            // in whatever rotation Sable's physics produced between
            // assembly and the first kinematic tick.
            lockedRotation = new Quaterniond();
            // spawnWorldPos: captured from Sable's currentPosition (= block
            // AABB centre — same reference Sable uses for the pose
            // translation; see SableShipyard.computeAnchor).
            spawnWorldPos = new Vector3d(input.currentPosition());
            // spawnGameTick: only overwrite if NOT pre-seeded by
            // {@link #preSeedSpawnTick}. If pre-seeded, keep the actual
            // spawn-time tick so canonicalPos starts ticking from the
            // correct time origin even when Sable's first tick is delayed.
            if (spawnGameTick == -1L) {
                spawnGameTick = currentGameTick;
                frozenTicksAtSpawn = TrainMotionFreeze.frozenTicks(trainId);
            }
            canonicalPos = new Vector3d(spawnWorldPos);
            // Apply any pre-seeded sub-block world-X nudge exactly once, now
            // that spawnWorldPos exists (shiftSpawnPosition no-ops before this
            // point). Carries the fractional inter-group gap the integer spawn
            // origin couldn't express. Both spawnWorldPos and canonicalPos move
            // so the deterministic formula extrapolates from the nudged origin.
            if (pendingSpawnShiftX != 0.0) {
                spawnWorldPos.x += pendingSpawnShiftX;
                canonicalPos.x += pendingSpawnShiftX;
                pendingSpawnShiftX = 0.0;
            }
            // Stamped AFTER the nudge lands, so it dates the final pose rather than the
            // quantised one the tracker must not judge.
            spawnCaptureGameTick = currentGameTick;
            lockedPositionInModel = new Vector3d(input.currentPositionInModel());
            JITTER_LOGGER.info(
                "[baseline] pIdx={} groupSize={} trainId={} forced identity lockedRotation; spawnWorldPos={} spawnGameTick={} lockedPositionInModel={}",
                pIdx, groupSize, trainId, fmt(spawnWorldPos), spawnGameTick, fmt(lockedPositionInModel));

            // [capture-lag] diagnostic (opt-in, off by default). spawnGameTick
            // is pre-seeded at assembly; spawnWorldPos is captured HERE on the
            // first physics tick. A non-zero lagTicks means this carriage's
            // world baseline was sampled lagTicks after assembly — bounds H4
            // (the lazy-capture offset of velocity*lag). Cross-reference deltaX
            // against the matching [bwd-place] idealX for this pIdx; H4 is ruled
            // out as the GROWING cause unless |deltaX| rises with backward pIdx.
            if (TrainCarriageAppender.isSeamGapTraceEnabled()) {
                long lagTicks = currentGameTick - spawnGameTick;
                JITTER_LOGGER.info(
                    "[capture-lag] pIdx={} trainId={} spawnGameTick={} captureGameTick={} lagTicks={} spawnWorldPosX={} velocityX={}",
                    pIdx, trainId, spawnGameTick, currentGameTick, lagTicks,
                    String.format("%.4f", spawnWorldPos.x), String.format("%.4f", targetVelocity.x()));
            }
        }

        // Deterministic position formula:
        //   canonicalPos = spawnWorldPos + velocity * (currentGameTick − spawnGameTick) * PHYSICS_DT
        //
        // Resilient to chunk unload/reload: a sub-level whose chunks are
        // evicted does not tick, but each surviving tick re-derives
        // canonicalPos from scratch using the level's monotonic gameTime
        // — so missed ticks don't accumulate as positional drift. The
        // previous "canonicalPos.add(velocity*dt)" approach lagged a
        // sub-level by N*velocity*dt blocks if it was unloaded for N
        // ticks, producing the half-carriage overlap/gap pattern when
        // the player flew back to a previously-visited part of the train.
        double prevCanonX = canonicalPos.x;
        double prevCanonY = canonicalPos.y;
        double prevCanonZ = canonicalPos.z;
        // Hold a freshly-(re)loaded carriage at its spawn position until this
        // dimension's world-load grace window expires, then advance smoothly
        // (elapsed steps 0 → 0 → 1, no jump). A no-op for every carriage
        // appended during normal play (holdUntil is already in the past, so the
        // effective origin stays spawnGameTick). See beginLoadGrace /
        // WORLD_LOAD_MOTION_GRACE_TICKS.
        long holdUntil = MOTION_HOLD_UNTIL.getOrDefault(dimensionKey, Long.MIN_VALUE);
        // And stand still for as long as this train is frozen — the ticks it spends stopped are
        // simply not counted, so it neither advances now nor jumps when it starts again. Zero for
        // every train that has never frozen, which makes this line a no-op for all of them.
        long frozenSinceSpawn = TrainMotionFreeze.frozenTicks(trainId) - frozenTicksAtSpawn;
        long elapsedTicks = effectiveElapsedTicks(currentGameTick, spawnGameTick, holdUntil,
            frozenSinceSpawn);
        canonicalPos.set(
            spawnWorldPos.x + targetVelocity.x() * elapsedTicks * PHYSICS_DT,
            spawnWorldPos.y + targetVelocity.y() * elapsedTicks * PHYSICS_DT,
            spawnWorldPos.z + targetVelocity.z() * elapsedTicks * PHYSICS_DT
        );
        if (!Double.isFinite(canonicalPos.x)
            || !Double.isFinite(canonicalPos.y)
            || !Double.isFinite(canonicalPos.z)) {
            if (!canonicalPosNanLogged) {
                JITTER_LOGGER.warn(
                    "[panic.canonicalPos] non-finite canonicalPos after deterministic step — freezing at last good value. "
                        + "pIdx={} trainId={} physicsTick={} elapsedTicks={} velocity=({}, {}, {}) spawnWorldPos=({}, {}, {}) canonicalBefore=({}, {}, {}) canonicalAfter=({}, {}, {})",
                    pIdx, trainId, physicsTickCounter, elapsedTicks,
                    targetVelocity.x(), targetVelocity.y(), targetVelocity.z(),
                    spawnWorldPos.x, spawnWorldPos.y, spawnWorldPos.z,
                    prevCanonX, prevCanonY, prevCanonZ,
                    canonicalPos.x, canonicalPos.y, canonicalPos.z);
                canonicalPosNanLogged = true;
            }
            canonicalPos.set(prevCanonX, prevCanonY, prevCanonZ);
        }

        // Resume-after-cull re-anchor. When this sub-level was culled to
        // holding it dropped out of SableKinematicTicker and stopped being
        // ticked; on reload gameTime has jumped by the missed ticks, so the
        // deterministic step above just caught canonicalPos up to its correct
        // (sibling-aligned) position in a single frame. Re-base the spawn
        // anchor onto that already-correct position so future ticks extrapolate
        // from here — the emitted absolute position is bit-identical (zero
        // drift), only the internal basis moves — and reset the tripwire
        // baseline so the expected one-frame catch-up doesn't fire the probe.
        // The >1 gap guard (see shouldReanchor) keeps the tripwire fully armed
        // for TRUE anomalies: a large delta with NO tick gap is still a real
        // regression and still fires.
        if (shouldReanchor(lastNextTransformGameTick, currentGameTick)) {
            spawnWorldPos.set(canonicalPos);
            spawnGameTick = currentGameTick;
            // With the elapsed count now measured from here, the frozen ticks before here are
            // already baked into the position we just re-based onto. Carrying the old baseline
            // would subtract every one of them a second time.
            frozenTicksAtSpawn = TrainMotionFreeze.frozenTicks(trainId);
            if (prevEffectivePos != null) prevEffectivePos.set(canonicalPos);
        }
        lastNextTransformGameTick = currentGameTick;

        TickOutput next = computeCompensatedTransform(input);
        logPhysicsProbe(input, next);
        return next;
    }

    /**
     * Full-precision Vector3d formatter for jitter logs. Six decimal places
     * keep sub-mm precision visible across the MC world bounds.
     */
    public static String fmt(Vector3dc v) {
        if (v == null) return "null";
        return String.format("(%.6f, %.6f, %.6f)", v.x(), v.y(), v.z());
    }

    /**
     * Residual jitter probe. {@code rawComDeltaX} should stay at 0: appends no
     * longer move the pivot, and CarriagePivotPin re-pins it on the block-change
     * choke point before this runs. Non-zero here means the pin missed a path —
     * a regression worth investigating.
     */
    private void logPhysicsProbe(TickInput current, TickOutput nextTransform) {
        physicsTickCounter++;
        Vector3dc effPos = nextTransform.position();
        Vector3dc pivot = current.currentPositionInModel();
        Vector3dc pivotInReturned = nextTransform.positionInModel();
        double rawComDeltaX = pivot.x() - lockedPositionInModel.x();

        Vector3d voxelA = new Vector3d(pivotInReturned).negate();
        lockedRotation.transform(voxelA);
        voxelA.add(effPos);

        String src = Math.abs(rawComDeltaX) < PIVOT_MOVE_EPSILON ? "ours" : "VS";

        if (prevEffectivePos != null && tripwireCooldown == 0) {
            double dx = effPos.x() - prevEffectivePos.x;
            double dy = effPos.y() - prevEffectivePos.y;
            double dz = effPos.z() - prevEffectivePos.z;
            double deltaLen = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double expectedMax = Math.sqrt(
                targetVelocity.x * targetVelocity.x
                    + targetVelocity.y * targetVelocity.y
                    + targetVelocity.z * targetVelocity.z
            ) * PHYSICS_DT * JITTER_TRIPWIRE_MULTIPLIER;
            if (deltaLen > expectedMax) {
                JITTER_LOGGER.warn(
                    "[tripwire] pIdx={} physicsTick={} deltaLen={} expectedMax={} canonicalPos={} pivotNow={} pivotLocked={} effPos={} prevEffPos={} src={}",
                    pIdx, physicsTickCounter, deltaLen, expectedMax, fmt(canonicalPos), fmt(pivot),
                    fmt(lockedPositionInModel), fmt(effPos), fmt(prevEffectivePos), src);
                tripwireCooldown = JITTER_TRIPWIRE_COOLDOWN_TICKS;
            }
        }
        if (tripwireCooldown > 0) tripwireCooldown--;

        long nowNanos = System.nanoTime();
        long mutNanos = lastMutationNanos;
        long msSinceMutation = mutNanos == 0L ? -1L : (nowNanos - mutNanos) / 1_000_000L;
        boolean mutationDriven = msSinceMutation >= 0L && msSinceMutation < 100L;

        if (!Double.isNaN(lastPivotX)) {
            double pdx = pivot.x() - lastPivotX;
            double pdy = pivot.y() - lastPivotY;
            double pdz = pivot.z() - lastPivotZ;
            if (Math.abs(pdx) > PIVOT_MOVE_EPSILON
                || Math.abs(pdy) > PIVOT_MOVE_EPSILON
                || Math.abs(pdz) > PIVOT_MOVE_EPSILON) {
                JITTER_LOGGER.trace(
                    "[pivotMoved] pIdx={} physicsTick={} pivotNow={} delta=({}, {}, {}) src={} lastMutationTick={} msSinceMutation={} mutationDriven={}",
                    pIdx, physicsTickCounter, fmt(pivot),
                    String.format("%.6f", pdx),
                    String.format("%.6f", pdy),
                    String.format("%.6f", pdz),
                    src, lastMutationTick, msSinceMutation, mutationDriven);
            }
        }
        lastPivotX = pivot.x();
        lastPivotY = pivot.y();
        lastPivotZ = pivot.z();

        boolean comDeltaChanged = Double.isNaN(lastLoggedComDeltaX)
            || Math.abs(rawComDeltaX - lastLoggedComDeltaX) > JITTER_COMDELTA_LOG_STEP;
        if (physicsTickCounter % JITTER_DEBUG_PERIOD == 0 || comDeltaChanged) {
            JITTER_LOGGER.trace(
                "[physics] pIdx={} physicsTick={} src={} canonicalPos={} pivotNow={} pivotLocked={} rawComDeltaX={} effPos={} voxelA_world={} lastMutationTick={} msSinceMutation={}",
                pIdx, physicsTickCounter, src,
                fmt(canonicalPos), fmt(pivot), fmt(lockedPositionInModel),
                String.format("%.6f", rawComDeltaX),
                fmt(effPos), fmt(voxelA), lastMutationTick, msSinceMutation);
            lastLoggedComDeltaX = rawComDeltaX;
        }

        if (prevEffectivePos == null) prevEffectivePos = new Vector3d(effPos);
        else prevEffectivePos.set(effPos);
    }
}
