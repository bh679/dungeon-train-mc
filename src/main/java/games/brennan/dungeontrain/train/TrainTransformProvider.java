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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Drives a VS ship at a fixed world-space velocity by prescribing its next
 * transform every physics tick — a kinematic alternative to force-based
 * physics that bypasses the VS/Bullet mass threshold we hit with
 * TrainForcesInducer at count=20.
 *
 * Entity ride-along still works: VS uses the returned {@code nextVel} to
 * compute carry-along for entities standing on the ship's blocks.
 *
 * This class is used as the Dungeon Train marker — a loaded ship whose
 * transform provider is a {@code TrainTransformProvider} is one of ours.
 * Window state (shipyard origin, carriage count, active indices) lives
 * here so the rolling-window manager can read/write it via one cast.
 */
public final class TrainTransformProvider implements KinematicDriver {

    private static final Logger LOGGER = LogUtils.getLogger();
    // Diagnostic logger for the flatbed-hop investigation (Stage 1 of the
    // adoring-mestorf-a360ce branch). Elevated to DEBUG in DungeonTrain's
    // constructor so these lines survive Forge's default INFO root level.
    // Remove or demote to TRACE once the root cause is confirmed.
    private static final Logger JITTER_LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");

    // VS physics thread runs at 60 Hz; each call to provideNextTransformAndVelocity
    // represents one physics step.
    private static final double PHYSICS_DT = 1.0 / 60.0;
    private static final Vector3dc ZERO_OMEGA = new Vector3d();
    // Squared block-units threshold for logging a pivot drift event — anything
    // smaller is floating-point noise that isn't worth a log line.
    private static final double COM_DRIFT_LOG_THRESHOLD_SQ = 0.01;

    // Jitter instrumentation constants.
    private static final int JITTER_DEBUG_PERIOD = 20;
    private static final double JITTER_COMDELTA_LOG_STEP = 0.1;
    private static final double JITTER_TRIPWIRE_MULTIPLIER = 2.0;
    private static final int JITTER_TRIPWIRE_COOLDOWN_TICKS = 10;

    private volatile Vector3d targetVelocity;
    private final BlockPos shipyardOrigin;
    private volatile int count;
    private final ResourceKey<Level> dimensionKey;
    private final Set<Integer> activeIndices;
    // Train-chain metadata — populated by TrainChainManager when it spawns
    // a successor train for this one. Prevents duplicate successors and
    // caps the rolling-window forward edge so this ship stops painting
    // carriages past the seam. See plans/floofy-floating-dahl.md.
    private volatile int forwardLimit = Integer.MAX_VALUE;
    private volatile int backwardLimit = Integer.MIN_VALUE;
    private volatile boolean successorSpawned = false;
    // Offset added to local pIdx for HUD display — the first train in a
    // chain reads 0, the second reads (predecessor's base + predecessor's
    // trigger pIdx), and so on.
    private final int globalPIdxBase;
    /**
     * Carriage footprint snapshot captured at spawn time. Immutable for the
     * train's lifetime — matches the "spawn-time-captured, read-only
     * afterward" pattern used by {@link #shipyardOrigin} and
     * {@link #trackGeometry}. Lets {@link TrainWindowManager} compute
     * carriage indices and block-placement offsets without round-tripping
     * through {@code DungeonTrainWorldData.get(level).dims()} on every tick.
     */
    private final CarriageDims dims;

    // Lazily captured on the first physics tick so the ship's spawn-time
    // orientation, world position, and model-space pivot become the
    // authoritative baseline. Re-applying them every tick makes the train
    // immune to gravity, collision impulses, and COM-shift side effects
    // from rolling-window block mutations.
    private Quaterniondc lockedRotation;
    private Vector3d canonicalPos;
    private Vector3dc lockedPositionInModel;
    private double lastLoggedDriftX = Double.NaN;

    // Reversal-debounce state used by {@link TrainWindowManager} to suppress
    // single-tick flaps in the player's pIdx (which otherwise cause a visible
    // whole-train teleport each time the window shifts back-and-forth).
    private int committedPIdx;
    private int lastShiftDirection;
    private long lastShiftTick;

    // Jitter-probe state (Stage 1 + 2b, see `.claude/plans/swirling-fluttering-squid.md`).
    // Physics thread ownership — no volatile needed; never read from another thread.
    private long physicsTickCounter;
    private Vector3d prevEffectivePos;
    private double lastLoggedComDeltaX = Double.NaN;
    private int tripwireCooldown;
    // Stage 2b: track previous pivot to catch ANY movement (even sub-block
    // floating-point noise-level drifts) between physics ticks. Answers
    // "is the COM moving between mutations?" directly.
    private double lastPivotX = Double.NaN;
    private double lastPivotY = Double.NaN;
    private double lastPivotZ = Double.NaN;
    // Sub-mm threshold for "the pivot moved" events so we don't log every
    // floating-point round-off. Any real COM recalc will produce deltas far
    // larger than this.
    private static final double PIVOT_MOVE_EPSILON = 1e-6;
    // One-shot guard so the [panic.canonicalPos] line fires once per
    // train spawn — otherwise a sustained NaN would spam every physics
    // tick (60 Hz). The freeze itself stays in effect; this only gates
    // the log line.
    private boolean canonicalPosNanLogged;
    // Written by the server thread (TrainWindowManager) and read by the server
    // thread (TrainTickEvents' H4 carry probe) on the same tick — same thread
    // invocation chain, so no synchronization required.
    private long lastMutationTick = -1L;

    // Stage 2b Option B — timestamp of the most recent block mutation (nanos).
    // Written on the server thread when a mutation happens; read on the physics
    // thread to compute msSinceMutation. Volatile because cross-thread long
    // reads aren't atomic on all JVMs. Any read is a point-in-time snapshot —
    // exact ordering doesn't matter, only ballpark magnitude.
    private volatile long lastMutationNanos = 0L;

    public TrainTransformProvider(
        Vector3dc targetVelocity,
        BlockPos shipyardOrigin,
        int count,
        ResourceKey<Level> dimensionKey,
        int initialPIdx,
        CarriageDims dims
    ) {
        this(targetVelocity, shipyardOrigin, count, dimensionKey, initialPIdx, dims, 0);
    }

    public TrainTransformProvider(
        Vector3dc targetVelocity,
        BlockPos shipyardOrigin,
        int count,
        ResourceKey<Level> dimensionKey,
        int initialPIdx,
        CarriageDims dims,
        int globalPIdxBase
    ) {
        this.targetVelocity = new Vector3d(targetVelocity);
        this.shipyardOrigin = shipyardOrigin.immutable();
        this.count = count;
        this.dimensionKey = dimensionKey;
        this.dims = dims;
        this.globalPIdxBase = globalPIdxBase;
        this.activeIndices = new HashSet<>();
        int halfBack = (count - 1) / 2;
        int halfFront = count - halfBack - 1;
        for (int i = initialPIdx - halfBack; i <= initialPIdx + halfFront; i++) {
            this.activeIndices.add(i);
        }
        this.committedPIdx = initialPIdx;
        this.lastShiftDirection = 0;
        this.lastShiftTick = 0L;
    }

    public int getForwardLimit() {
        return forwardLimit;
    }

    public void setForwardLimit(int limit) {
        this.forwardLimit = limit;
    }

    public int getBackwardLimit() {
        return backwardLimit;
    }

    public void setBackwardLimit(int limit) {
        this.backwardLimit = limit;
    }

    public boolean isSuccessorSpawned() {
        return successorSpawned;
    }

    public void setSuccessorSpawned(boolean v) {
        this.successorSpawned = v;
    }

    public int getGlobalPIdxBase() {
        return globalPIdxBase;
    }

    public Vector3dc getTargetVelocity() {
        return targetVelocity;
    }

    /**
     * Replace the target velocity with a fresh vector. Safe to call from any
     * thread — the physics tick reads {@code targetVelocity} via the volatile
     * reference and never mutates it in place, so reassigning a new object
     * gives safe publication without torn reads.
     */
    public void setTargetVelocity(Vector3dc v) {
        this.targetVelocity = new Vector3d(v);
    }

    public BlockPos getShipyardOrigin() {
        return shipyardOrigin;
    }

    public int getCount() {
        return count;
    }

    /**
     * Update the rolling-window carriage count. {@link TrainWindowManager}
     * reads this on its next tick and adds or erases carriage blocks to match.
     */
    public void setCount(int newCount) {
        this.count = newCount;
    }

    public ResourceKey<Level> getDimensionKey() {
        return dimensionKey;
    }

    /**
     * Carriage footprint for this train — set at construction time, never
     * changes. Use from tick-hot-path consumers ({@link TrainWindowManager},
     * {@link TrainAssembler}) to avoid a {@code DungeonTrainWorldData} lookup
     * per tick.
     */
    public CarriageDims dims() {
        return dims;
    }

    public Set<Integer> getActiveIndices() {
        return activeIndices;
    }

    public int getCommittedPIdx() {
        return committedPIdx;
    }

    public void setCommittedPIdx(int committedPIdx) {
        this.committedPIdx = committedPIdx;
    }

    public int getLastShiftDirection() {
        return lastShiftDirection;
    }

    public void setLastShiftDirection(int lastShiftDirection) {
        this.lastShiftDirection = lastShiftDirection;
    }

    public long getLastShiftTick() {
        return lastShiftTick;
    }

    public void setLastShiftTick(long lastShiftTick) {
        this.lastShiftTick = lastShiftTick;
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

    /**
     * Read-only view of the physics-thread tick counter for the
     * stuck-player panic dump in {@code TrainTickEvents}. Server thread
     * reads, physics thread writes; the value is a long, which is not
     * guaranteed atomic on all JVMs — for diagnostics a slightly stale
     * read is fine.
     */
    public long getPhysicsTickCounter() {
        return physicsTickCounter;
    }

    /**
     * True if {@link #canonicalPos} has been captured (first physics tick
     * has run) and all components are finite. Used by the panic detector
     * to flag NaN/∞ propagation as a possible cause of "player stuck".
     */
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
     * Advance the ship's reference frame forward by {@code shipyardDelta}.
     * Used by {@link ShipyardShifter} to keep the pivot close to the
     * player's current shipyard position so active carriage voxels stay
     * within VS 2.4.11's 128-chunk distance limit from the ship's reference
     * point. See {@code plans/floofy-floating-dahl.md}.
     *
     * <h2>Math</h2>
     * {@code voxel_world = canonicalPos + rotation·(voxel_shipyard − pivot)}.
     * Setting {@code pivot += Δ_shipyard} and {@code canonicalPos += rotation·Δ}
     * leaves every {@code voxel_world} unchanged — the ship's visible
     * carriages don't hop. The physics-thread jitter probe
     * ({@link #provideNextTransformAndVelocity}) reads {@code canonicalPos}
     * at the start of each tick; a concurrent add here produces at most a
     * one-tick visual stagger, which self-corrects on the next read.
     *
     * @param shipyardDelta the vector to add to the shipyard-space pivot —
     *     typically {@code (shiftBlocks, 0, 0)} for a straight-line train.
     */
    public void shiftReference(Vector3dc shipyardDelta) {
        if (lockedPositionInModel == null || canonicalPos == null) return;
        Vector3d worldDelta = new Vector3d(shipyardDelta);
        if (lockedRotation != null) lockedRotation.transform(worldDelta);
        canonicalPos.add(worldDelta);
        // Replace the reference — consumers that already captured the old
        // Vector3dc aren't reading it mid-tick, so swapping pointers is
        // equivalent to mutating in place but gives a cleaner read
        // boundary.
        lockedPositionInModel = new Vector3d(lockedPositionInModel).add(shipyardDelta);
    }

    /**
     * Snapshot of the ship's inertia at spawn time. When non-null, {@link
     * TrainWindowManager} writes these values back onto the ship after every
     * voxel mutation so the underlying physics mod doesn't move
     * {@code positionInModel}. See {@code ship.vs.VsInertiaLocker} for the
     * VS-specific reflection mechanism.
     */
    private volatile InertiaSnapshot lockedInertia;

    public InertiaSnapshot getLockedInertia() {
        return lockedInertia;
    }

    public void setLockedInertia(InertiaSnapshot locked) {
        this.lockedInertia = locked;
    }

    /**
     * World-space track layout under this train. Null until
     * {@link TrainAssembler#spawnTrain} attaches one at assembly time.
     * Written once on the server thread; read on both the server thread
     * (TrackChunkEvents, periodic tick) and — never on the physics thread.
     */
    private volatile TrackGeometry trackGeometry;

    public TrackGeometry getTrackGeometry() {
        return trackGeometry;
    }

    public void setTrackGeometry(TrackGeometry geometry) {
        this.trackGeometry = geometry;
    }

    /**
     * ChunkPos longs (see {@link net.minecraft.world.level.ChunkPos#asLong})
     * of chunks we've already filled with tracks. The periodic scan and the
     * chunk-load listener both consult this set before iterating block
     * columns, so re-visiting a chunk is an O(1) hit lookup.
     *
     * <p>ConcurrentHashMap-backed set — both the server thread (periodic
     * tick, chunk events) and future threads can touch it safely.</p>
     */
    private final Set<Long> filledChunks = ConcurrentHashMap.newKeySet();

    public Set<Long> getFilledChunks() {
        return filledChunks;
    }

    /**
     * ChunkPos longs queued for deferred filling. The {@code ChunkEvent.Load}
     * listener only <em>enqueues</em> here (no synchronous setBlock) because
     * painting on the load tick was observed to wedge the server thread for
     * 17+ seconds while VS was still settling a freshly-spawned ship.
     *
     * <p>Drained at the rate-limited {@code TrackGenerator.fillRenderDistance}
     * budget (see {@code CHUNKS_PER_SCAN_BUDGET}), so a burst of chunk loads
     * at login spreads its block writes across many ticks instead of one.</p>
     *
     * <p>ConcurrentLinkedDeque (not a HashSet) so drain order matches
     * chunk-load order — chunks near the player paint before chunks far
     * away, giving a visually contiguous bed instead of the hash-scattered
     * patchwork we got with HashSet iteration. Dedup is handled at drain
     * time via {@link #filledChunks}; occasional duplicates in the queue
     * are harmless.</p>
     */
    private final Deque<Long> pendingChunks = new ConcurrentLinkedDeque<>();

    public Deque<Long> getPendingChunks() {
        return pendingChunks;
    }

    /**
     * ChunkPos longs of chunks already processed by
     * {@link games.brennan.dungeontrain.tunnel.TunnelGenerator}. Parallel to
     * {@link #filledChunks} — the tunnel fill is a separate pass with a
     * wider corridor (±4 vs ±2 on Z), a different qualification check
     * (underground materials over the ceiling), and its own phase offset in
     * {@link games.brennan.dungeontrain.event.TrainTickEvents}.
     */
    private final Set<Long> tunnelFilledChunks = ConcurrentHashMap.newKeySet();

    public Set<Long> getTunnelFilledChunks() {
        return tunnelFilledChunks;
    }

    /** ChunkPos longs queued for deferred tunnel filling — parallel to {@link #pendingChunks}. */
    private final Set<Long> pendingTunnelChunks = ConcurrentHashMap.newKeySet();

    public Set<Long> getPendingTunnelChunks() {
        return pendingTunnelChunks;
    }

    /**
     * Compute the compensated tick output for a given observed ship state.
     *
     * Shared between the physics-thread driver callback (normal path) and the
     * rolling-window manager (called on the server thread right after block
     * mutations) so the voxel change and transform update land in the same
     * server tick — otherwise the client may render the new voxels against the
     * stale transform for one frame, producing a visible teleport.
     *
     * Returns {@code null} if the baseline hasn't been captured yet (no
     * physics tick has run); callers should leave the transform alone in that
     * case.
     */
    public TickOutput computeCompensatedTransform(TickInput current) {
        if (lockedRotation == null) return null;

        Vector3d effectivePos = computeEffectivePosition(
            canonicalPos, current.currentPositionInModel(), lockedPositionInModel, lockedRotation);

        double rawComDeltaX = current.currentPositionInModel().x() - lockedPositionInModel.x();
        if (rawComDeltaX * rawComDeltaX > COM_DRIFT_LOG_THRESHOLD_SQ
            && Math.abs(rawComDeltaX - lastLoggedDriftX) > 0.5) {
            LOGGER.debug("[DungeonTrain] Pivot drift: rawComDeltaX={} — compensating", rawComDeltaX);
            lastLoggedDriftX = rawComDeltaX;
        }

        return new TickOutput(
            effectivePos,
            lockedRotation,
            current.currentPositionInModel(),
            targetVelocity,
            ZERO_OMEGA);
    }

    /**
     * Pure-logic helper for the pivot-drift compensation math. Extracted so
     * unit tests can exercise the arithmetic without mocking VS's
     * {@link KinematicDriver.TickInput} / {@link KinematicDriver.TickOutput} types.
     *
     * Formula: {@code effectivePos = canonicalPos + lockedRotation · (currentPivot − lockedPivot)}.
     *
     * @return a newly-allocated {@link Vector3d} — callers own the result.
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

    @Override
    public TickOutput nextTransform(TickInput input) {
        if (lockedRotation == null) {
            lockedRotation = new Quaterniond(input.currentRotation());
            canonicalPos = new Vector3d(input.currentPosition());
            lockedPositionInModel = new Vector3d(input.currentPositionInModel());
            // Stage 2b P4 — sanity-log the captured baseline. Non-identity
            // rotation would change the sign convention of the compensation
            // math and matter a lot for diagnosis; print it once.
            JITTER_LOGGER.info(
                "[baseline] captured lockedRotation=({}, {}, {}, {}) canonicalPos={} lockedPositionInModel={}",
                lockedRotation.x(), lockedRotation.y(), lockedRotation.z(), lockedRotation.w(),
                fmt(canonicalPos), fmt(lockedPositionInModel));
        }

        // Snapshot the previous canonical position so we can roll back if
        // the velocity step produces NaN/∞ — propagating a non-finite
        // canonical position would corrupt every downstream transform and
        // make the "stuck" symptom permanent rather than recoverable. Cheap
        // (one Vector3d allocation per physics tick).
        double prevCanonX = canonicalPos.x;
        double prevCanonY = canonicalPos.y;
        double prevCanonZ = canonicalPos.z;
        canonicalPos.add(
            targetVelocity.x() * PHYSICS_DT,
            targetVelocity.y() * PHYSICS_DT,
            targetVelocity.z() * PHYSICS_DT
        );
        if (!Double.isFinite(canonicalPos.x)
            || !Double.isFinite(canonicalPos.y)
            || !Double.isFinite(canonicalPos.z)) {
            if (!canonicalPosNanLogged) {
                JITTER_LOGGER.warn(
                    "[panic.canonicalPos] non-finite canonicalPos after velocity step — freezing at last good value. "
                        + "physicsTick={} velocity=({}, {}, {}) canonicalBefore=({}, {}, {}) canonicalAfter=({}, {}, {})",
                    physicsTickCounter,
                    targetVelocity.x(), targetVelocity.y(), targetVelocity.z(),
                    prevCanonX, prevCanonY, prevCanonZ,
                    canonicalPos.x, canonicalPos.y, canonicalPos.z);
                canonicalPosNanLogged = true;
            }
            canonicalPos.set(prevCanonX, prevCanonY, prevCanonZ);
        }

        // The rolling-window manager mutates voxel blocks every server tick;
        // VS may re-center the ship's model-space pivot (positionInModel) on
        // the new COM regardless of setStatic(true). Setting the output's
        // positionInModel to our locked baseline is not enough — VS appears
        // to ignore or re-derive it. So we COMPENSATE the world-space
        // position by the observed pivot drift, which keeps the voxel-to-
        // world mapping anchored to the original pivot no matter which pivot
        // VS actually uses for rendering.
        TickOutput next = computeCompensatedTransform(input);
        logPhysicsProbe(input, next);
        return next;
    }

    /**
     * Full-precision Vector3d formatter for jitter logs. The default
     * {@code Vector3d.toString()} uses scientific notation with ~4
     * significant figures, which hides sub-block drift in pivots that
     * sit at huge coordinate magnitudes (e.g. {@code -2.867e7} can differ
     * by tens of blocks yet print identically). Six decimal places are
     * enough for sub-mm precision across the MC world bounds.
     */
    public static String fmt(Vector3dc v) {
        if (v == null) return "null";
        return String.format("(%.6f, %.6f, %.6f)", v.x(), v.y(), v.z());
    }

    /**
     * Stage 1 + 2b jitter probe — emits structured DEBUG lines covering
     * every COM/pivot change, the ship's next transform, and a WARN
     * tripwire on any physics-tick position jump that exceeds the
     * expected straight-line velocity step. Answers the direct question
     * "is the COM moving, and if so, when and by how much?".
     */
    private void logPhysicsProbe(TickInput current, TickOutput nextTransform) {
        physicsTickCounter++;
        Vector3dc effPos = nextTransform.position();
        Vector3dc pivot = current.currentPositionInModel();
        Vector3dc pivotInReturned = nextTransform.positionInModel();
        double rawComDeltaX = pivot.x() - lockedPositionInModel.x();

        // Stage 2b P2 — voxel world-position sample. Voxel A = shipyard origin
        // (model-space (0,0,0)). voxelWorld = effPos + rotation · (0 - pivotInReturned).
        // If voxelA_world oscillates across physics ticks, voxels are hopping;
        // if only effPos oscillates, voxels are stable and the hop is in ship-
        // frame only (which should be invisible given correct client rendering).
        Vector3d voxelA = new Vector3d(pivotInReturned).negate();
        lockedRotation.transform(voxelA);
        voxelA.add(effPos);

        // Stage 2b P6 — label pivot source. "ours" means VS honoured our
        // lockedPivot override (comDelta=0); "VS" means VS re-derived its
        // own pivot.
        String src = Math.abs(rawComDeltaX) < PIVOT_MOVE_EPSILON ? "ours" : "VS";

        // Tripwire on effPos delta (Stage 1, kept as-is).
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
                    "[tripwire] physicsTick={} deltaLen={} expectedMax={} canonicalPos={} pivotNow={} pivotLocked={} effPos={} prevEffPos={} src={}",
                    physicsTickCounter, deltaLen, expectedMax, fmt(canonicalPos), fmt(pivot),
                    fmt(lockedPositionInModel), fmt(effPos), fmt(prevEffectivePos), src);
                tripwireCooldown = JITTER_TRIPWIRE_COOLDOWN_TICKS;
            }
        }
        if (tripwireCooldown > 0) tripwireCooldown--;

        // Stage 2b Option B — unconditional "pivot moved" probe. Fires on
        // ANY change in current.getPositionInModel() between consecutive
        // physics ticks, even sub-0.1-block drift. Tags each event with
        // msSinceMutation so we can distinguish mutation-driven moves
        // (small delta, expected) from spontaneous VS-internal moves
        // (large delta, indicates VS recalculates independently of block
        // changes — would require a different fix).
        //
        // Reads lastMutationNanos across the server/physics thread
        // boundary. The field is volatile; a slightly stale read is fine
        // because msSinceMutation is a diagnostic signal, not a correctness
        // dependency.
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
                    "[pivotMoved] physicsTick={} pivotNow={} delta=({}, {}, {}) src={} lastMutationTick={} msSinceMutation={} mutationDriven={}",
                    physicsTickCounter, fmt(pivot),
                    String.format("%.6f", pdx),
                    String.format("%.6f", pdy),
                    String.format("%.6f", pdz),
                    src, lastMutationTick, msSinceMutation, mutationDriven);
            }
        }
        lastPivotX = pivot.x();
        lastPivotY = pivot.y();
        lastPivotZ = pivot.z();

        // Periodic [physics] sample (Stage 1, full-precision now).
        boolean comDeltaChanged = Double.isNaN(lastLoggedComDeltaX)
            || Math.abs(rawComDeltaX - lastLoggedComDeltaX) > JITTER_COMDELTA_LOG_STEP;
        if (physicsTickCounter % JITTER_DEBUG_PERIOD == 0 || comDeltaChanged) {
            JITTER_LOGGER.trace(
                "[physics] physicsTick={} src={} canonicalPos={} pivotNow={} pivotLocked={} rawComDeltaX={} effPos={} voxelA_world={} lastMutationTick={} msSinceMutation={}",
                physicsTickCounter, src,
                fmt(canonicalPos), fmt(pivot), fmt(lockedPositionInModel),
                String.format("%.6f", rawComDeltaX),
                fmt(effPos), fmt(voxelA), lastMutationTick, msSinceMutation);
            lastLoggedComDeltaX = rawComDeltaX;
        }

        if (prevEffectivePos == null) prevEffectivePos = new Vector3d(effPos);
        else prevEffectivePos.set(effPos);
    }
}
