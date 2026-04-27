package games.brennan.dungeontrain.ship;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;

/**
 * Handle for one managed ship — a single train-shaped rigid body with its
 * own model-space coordinate system.
 *
 * <p>Obtained from {@link Shipyard#findAll()}, {@link Shipyard#findAt} or
 * {@link Shipyard#assemble}. Implementations wrap the underlying physics-
 * mod's ship object (e.g. VS's {@code LoadedServerShip}).</p>
 *
 * <p>All transform queries operate on the live ship state — repeated calls
 * may return different values as the ship moves.</p>
 */
public interface ManagedShip {

    /** Unique identifier stable for the ship's lifetime. */
    long id();

    /**
     * Transform a world-space point into model-space, in place. Returns
     * the same {@link Vector3d} for chaining.
     */
    Vector3d worldToShip(Vector3d worldPos);

    /**
     * Transform a model-space point into world-space, in place. Returns
     * the same {@link Vector3d} for chaining.
     */
    Vector3d shipToWorld(Vector3d modelPos);

    /** Current world-space position of the ship's reference point. */
    Vector3dc currentWorldPosition();

    /** Current orientation. */
    Quaterniondc currentRotation();

    /** Current model-space pivot ({@code positionInModel} in VS terms). */
    Vector3dc currentPositionInModel();

    /** Snapshot of the current transform as a {@link KinematicDriver.TickInput}. */
    default KinematicDriver.TickInput currentTickInput() {
        return new KinematicDriver.TickInput(
            currentWorldPosition(),
            currentRotation(),
            currentPositionInModel());
    }

    /** Total world-space AABB covering every voxel claimed by the ship. */
    AABBdc worldAABB();

    /** Currently-assigned kinematic driver, or {@code null} if none. */
    @Nullable
    KinematicDriver getKinematicDriver();

    /**
     * Attach a kinematic driver. Replaces any previously-assigned driver;
     * physics integration is bypassed and the driver's
     * {@link KinematicDriver#nextTransform} is consulted each physics tick.
     */
    void setKinematicDriver(KinematicDriver driver);

    /**
     * Mark the ship as static — bypasses dynamics integration so block
     * mutations don't shift the ship's center-of-mass.
     */
    void setStatic(boolean isStatic);

    /**
     * Apply a kinematic tick output synchronously, updating the ship's
     * world transform without waiting for the next physics tick. Used by
     * the rolling-window manager to keep voxel-block changes and transform
     * updates landing on the same server tick.
     */
    void applyTickOutput(KinematicDriver.TickOutput output);

    /**
     * Capture a snapshot of the ship's current inertia state. Returns
     * {@code null} if the implementation cannot read inertia (e.g. a
     * physics mod that doesn't expose it, or a reflection failure).
     */
    @Nullable
    InertiaSnapshot captureInertia();

    /**
     * Overwrite the ship's live inertia fields with {@code snapshot}. No-op
     * if {@code snapshot} is {@code null} or the implementation cannot
     * write inertia.
     */
    void restoreInertia(@Nullable InertiaSnapshot snapshot);
}
