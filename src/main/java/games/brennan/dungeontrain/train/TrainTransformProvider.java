package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.TrackGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.valkyrienskies.core.api.bodies.properties.BodyTransform;
import org.valkyrienskies.core.api.ships.ServerShipTransformProvider;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;

import java.util.HashSet;
import java.util.Set;

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
public final class TrainTransformProvider implements ServerShipTransformProvider {

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
        int initialPIdx
    ) {
        this.targetVelocity = new Vector3d(targetVelocity);
        this.shipyardOrigin = shipyardOrigin.immutable();
        this.count = count;
        this.dimensionKey = dimensionKey;
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
     * Snapshot of the ship's inertia at spawn time. When non-null, {@link
     * TrainWindowManager} writes these values back onto the ship after every
     * voxel mutation so VS doesn't move {@code positionInModel}. See
     * {@link ShipInertiaLocker} for the mechanism.
     */
    private volatile ShipInertiaLocker.LockedInertia lockedInertia;

    public ShipInertiaLocker.LockedInertia getLockedInertia() {
        return lockedInertia;
    }

    public void setLockedInertia(ShipInertiaLocker.LockedInertia locked) {
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
     * Compute the compensated BodyTransform for a given observed ship state.
     *
     * Shared between the physics-thread provider callback (normal path) and the
     * rolling-window manager (called on the server thread right after block
     * mutations) so the voxel change and transform update land in the same
     * server tick — otherwise the client may render the new voxels against the
     * stale transform for one frame, producing a visible teleport.
     *
     * Returns {@code null} if the baseline hasn't been captured yet (no
     * physics tick has run); callers should leave the transform alone in that
     * case.
     */
    public BodyTransform computeCompensatedTransform(ShipTransform current) {
        if (lockedRotation == null) return null;

        Vector3d effectivePos = computeEffectivePosition(
            canonicalPos, current.getPositionInModel(), lockedPositionInModel, lockedRotation);

        double rawComDeltaX = current.getPositionInModel().x() - lockedPositionInModel.x();
        if (rawComDeltaX * rawComDeltaX > COM_DRIFT_LOG_THRESHOLD_SQ
            && Math.abs(rawComDeltaX - lastLoggedDriftX) > 0.5) {
            LOGGER.debug("[DungeonTrain] Pivot drift: rawComDeltaX={} — compensating", rawComDeltaX);
            lastLoggedDriftX = rawComDeltaX;
        }

        return current.toBuilder()
            .position(effectivePos)
            .rotation(lockedRotation)
            .build();
    }

    /**
     * Pure-logic helper for the pivot-drift compensation math. Extracted so
     * unit tests can exercise the arithmetic without mocking VS's
     * {@link ShipTransform} / {@link BodyTransform} interfaces.
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

    @NotNull
    @Override
    public NextTransformAndVelocityData provideNextTransformAndVelocity(
        @NotNull ShipTransform prev,
        @NotNull ShipTransform current
    ) {
        if (lockedRotation == null) {
            lockedRotation = new Quaterniond(current.getRotation());
            canonicalPos = new Vector3d(current.getPosition());
            lockedPositionInModel = new Vector3d(current.getPositionInModel());
            // Stage 2b P4 — sanity-log the captured baseline. Non-identity
            // rotation would change the sign convention of the compensation
            // math and matter a lot for diagnosis; print it once.
            JITTER_LOGGER.info(
                "[baseline] captured lockedRotation=({}, {}, {}, {}) canonicalPos={} lockedPositionInModel={}",
                lockedRotation.x(), lockedRotation.y(), lockedRotation.z(), lockedRotation.w(),
                fmt(canonicalPos), fmt(lockedPositionInModel));
        }

        canonicalPos.add(
            targetVelocity.x() * PHYSICS_DT,
            targetVelocity.y() * PHYSICS_DT,
            targetVelocity.z() * PHYSICS_DT
        );

        // The rolling-window manager mutates voxel blocks every server tick;
        // VS may re-center the ship's model-space pivot (positionInModel) on
        // the new COM regardless of setStatic(true). Setting the builder's
        // positionInModel to our locked baseline is not enough — VS appears
        // to ignore or re-derive it. So we COMPENSATE the world-space
        // position by the observed pivot drift, which keeps the voxel-to-
        // world mapping anchored to the original pivot no matter which pivot
        // VS actually uses for rendering.
        BodyTransform nextTransform = computeCompensatedTransform(current);
        logPhysicsProbe(current, nextTransform);
        return new NextTransformAndVelocityData(nextTransform, targetVelocity, ZERO_OMEGA);
    }

    /**
     * Full-precision Vector3d formatter for jitter logs. The default
     * {@code Vector3d.toString()} uses scientific notation with ~4
     * significant figures, which hides sub-block drift in pivots that
     * sit at huge coordinate magnitudes (e.g. {@code -2.867e7} can differ
     * by tens of blocks yet print identically). Six decimal places are
     * enough for sub-mm precision across the MC world bounds.
     */
    static String fmt(Vector3dc v) {
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
    private void logPhysicsProbe(ShipTransform current, BodyTransform nextTransform) {
        physicsTickCounter++;
        Vector3dc effPos = nextTransform.getPosition();
        Vector3dc pivot = current.getPositionInModel();
        Vector3dc pivotInReturned = nextTransform.getPositionInModel();
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
