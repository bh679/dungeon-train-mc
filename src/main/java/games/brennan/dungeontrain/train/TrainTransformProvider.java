package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
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

    // VS physics thread runs at 60 Hz; each call to provideNextTransformAndVelocity
    // represents one physics step.
    private static final double PHYSICS_DT = 1.0 / 60.0;
    private static final Vector3dc ZERO_OMEGA = new Vector3d();
    // Squared block-units threshold for logging a pivot drift event — anything
    // smaller is floating-point noise that isn't worth a log line.
    private static final double COM_DRIFT_LOG_THRESHOLD_SQ = 0.01;

    private final Vector3d targetVelocity;
    private final BlockPos shipyardOrigin;
    private final int count;
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

    public BlockPos getShipyardOrigin() {
        return shipyardOrigin;
    }

    public int getCount() {
        return count;
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

    public Vector3dc getLockedPositionInModel() {
        return lockedPositionInModel;
    }

    public Vector3dc getCanonicalPos() {
        return canonicalPos;
    }

    public Quaterniondc getLockedRotation() {
        return lockedRotation;
    }

    /**
     * Convert a world-space position to ship-local coordinates using the
     * provider's locked baseline (canonicalPos, lockedRotation, lockedPIM)
     * rather than the ship's live transform. This decouples rolling-window
     * logic (which computes pIdx from a world-space player position) from
     * the live PIM drift — {@code ship.getTransform().getWorldToShip()}
     * would otherwise bake the drifted PIM into the result, causing pIdx
     * to oscillate when VS moves PIM in response to mass-distribution
     * changes, which in turn drives the rolling-window feedback loop.
     *
     * Returns {@code null} if the provider hasn't captured its baseline
     * yet (no physics tick has run).
     */
    public Vector3d worldToLockedShip(double worldX, double worldY, double worldZ) {
        if (lockedRotation == null) return null;
        Vector3d rel = new Vector3d(
            worldX - canonicalPos.x,
            worldY - canonicalPos.y,
            worldZ - canonicalPos.z
        );
        // worldPos = canonicalPos + rot · (shipPos - lockedPIM)
        //   → shipPos = lockedPIM + invRot · (worldPos - canonicalPos)
        Quaterniond invRot = new Quaterniond(lockedRotation).invert();
        invRot.transform(rel);
        return rel.add(lockedPositionInModel);
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

        Vector3d comDelta = new Vector3d(current.getPositionInModel()).sub(lockedPositionInModel);
        if (comDelta.lengthSquared() > COM_DRIFT_LOG_THRESHOLD_SQ
            && Math.abs(comDelta.x - lastLoggedDriftX) > 0.5) {
            LOGGER.debug("[DungeonTrain] Pivot drift: comDelta=({}, {}, {}) — compensating",
                comDelta.x, comDelta.y, comDelta.z);
            lastLoggedDriftX = comDelta.x;
        }
        lockedRotation.transform(comDelta);
        Vector3d effectivePos = new Vector3d(canonicalPos).add(comDelta);

        return current.toBuilder()
            .position(effectivePos)
            .rotation(lockedRotation)
            .build();
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
        return new NextTransformAndVelocityData(nextTransform, targetVelocity, ZERO_OMEGA);
    }
}
