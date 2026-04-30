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
    // INFO root level. Mostly informational since the per-carriage
    // architecture eliminates COM drift at the source.
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
    // carriage immune to gravity, collision impulses, and any
    // (now-impossible-on-per-carriage) COM-shift side effects.
    private Quaterniondc lockedRotation;
    private Vector3d canonicalPos;
    private Vector3dc lockedPositionInModel;

    // When true, {@link TrainCarriageAppender} skips this carriage's train
    // entirely — no append regardless of player pIdx. Set by debug probes
    // (see {@code /dt debug pair}) so test fixtures stay exactly the size
    // they were assembled at; production carriages leave this false.
    private volatile boolean appenderDisabled = false;

    // Jitter-probe state. Owned by the physics tick caller — no volatile
    // needed; never read from another thread.
    private long physicsTickCounter;
    private Vector3d prevEffectivePos;
    private double lastLoggedComDeltaX = Double.NaN;
    private int tripwireCooldown;
    private double lastPivotX = Double.NaN;
    private double lastPivotY = Double.NaN;
    private double lastPivotZ = Double.NaN;
    // One-shot guard so the [panic.canonicalPos] line fires once per
    // sub-level spawn — otherwise a sustained NaN would spam every tick.
    private boolean canonicalPosNanLogged;
    // Set by callers that mutate the sub-level's blocks (currently nothing —
    // per-carriage means immutable post-assembly). Kept around because the
    // jitter probe references {@code lastMutationNanos} for diagnostics.
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
    private final Set<Long> tunnelFilledChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> pendingTunnelChunks = ConcurrentHashMap.newKeySet();

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

    public Set<Long> getTunnelFilledChunks() {
        return tunnelFilledChunks;
    }

    public Set<Long> getPendingTunnelChunks() {
        return pendingTunnelChunks;
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

    @Override
    public TickOutput nextTransform(TickInput input) {
        if (lockedRotation == null) {
            // Force identity rotation — the carriage must always be axis-
            // aligned. Capturing input.currentRotation() here would freeze
            // in whatever rotation Sable's physics produced between
            // assembly and the first kinematic tick.
            lockedRotation = new Quaterniond();
            canonicalPos = new Vector3d(input.currentPosition());
            lockedPositionInModel = new Vector3d(input.currentPositionInModel());
            JITTER_LOGGER.info(
                "[baseline] pIdx={} groupSize={} trainId={} forced identity lockedRotation; canonicalPos={} lockedPositionInModel={}",
                pIdx, groupSize, trainId, fmt(canonicalPos), fmt(lockedPositionInModel));
        }

        // Snapshot prior canonical so we can roll back NaN propagation.
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
                        + "pIdx={} trainId={} physicsTick={} velocity=({}, {}, {}) canonicalBefore=({}, {}, {}) canonicalAfter=({}, {}, {})",
                    pIdx, trainId, physicsTickCounter,
                    targetVelocity.x(), targetVelocity.y(), targetVelocity.z(),
                    prevCanonX, prevCanonY, prevCanonZ,
                    canonicalPos.x, canonicalPos.y, canonicalPos.z);
                canonicalPosNanLogged = true;
            }
            canonicalPos.set(prevCanonX, prevCanonY, prevCanonZ);
        }

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
     * Residual jitter probe. With per-carriage immutable blocks,
     * {@code rawComDeltaX} should stay at 0 — any drift is a regression
     * worth investigating.
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
