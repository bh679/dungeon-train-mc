package games.brennan.dungeontrain.ship;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

/**
 * Pluggable kinematic transform driver — the port equivalent of VS's
 * {@code ServerShipTransformProvider}. A driver computes the next physics
 * step's world transform and velocity for a {@link ManagedShip}, bypassing
 * the underlying physics integrator.
 *
 * <p>The Dungeon Train trains use one of these to follow a constant
 * world-space velocity at the cost of giving up dynamic interaction. See
 * {@code train.TrainTransformProvider}.</p>
 */
public interface KinematicDriver {

    /**
     * Snapshot of the ship's current physics-tick state passed into
     * {@link #nextTransform}.
     *
     * <p>{@code gameTime} is the level's current
     * {@code ServerLevel.getGameTime()} value (monotonic tick counter).
     * Drivers that need to be resilient against chunk unload/reload —
     * which freezes a sub-level's mutable state for the unload duration
     * — should compute their next pose deterministically from a captured
     * spawn-time pose plus {@code (gameTime − spawnGameTime) * PHYSICS_DT}
     * rather than incrementing per-tick state. See
     * {@link games.brennan.dungeontrain.train.TrainTransformProvider}.</p>
     */
    record TickInput(
        Vector3dc currentPosition,
        Quaterniondc currentRotation,
        Vector3dc currentPositionInModel,
        long gameTime
    ) {}

    /**
     * Next physics-tick state returned from {@link #nextTransform}. The
     * adapter applies this to the underlying ship — VS converts to
     * {@code BodyTransform} and {@code NextTransformAndVelocityData}.
     */
    record TickOutput(
        Vector3dc position,
        Quaterniondc rotation,
        Vector3dc positionInModel,
        Vector3dc linearVelocity,
        Vector3dc angularVelocity
    ) {}

    /**
     * Compute the next physics-tick transform and velocity from the
     * current state.
     */
    TickOutput nextTransform(TickInput input);

    /**
     * The model-space pivot this driver pins its ship to, or {@code null} if it has none
     * (or has not captured one yet).
     *
     * <p>Exists so {@code ship.sable.CarriagePivotPin} can re-pin a sub-level's
     * {@code Pose3d.rotationPoint} from the block-change path, where there is no
     * {@link TickOutput} to read {@link TickOutput#positionInModel()} from. Returning it
     * through the driver interface keeps {@code ship.sable} free of a {@code train.*}
     * cast.</p>
     *
     * <p>Default {@code null} — a driver that does not pin a pivot opts out, and the pin
     * becomes a no-op for its ship.</p>
     */
    @Nullable
    default Vector3dc lockedPositionInModel() {
        return null;
    }

    /**
     * Called when something outside this driver changed the ship's mass distribution and
     * the pivot had to be corrected for it — see {@code CarriagePivotPin}.
     *
     * <p>Diagnostics only; drivers may ignore it. Fires only when the pivot had
     * <em>actually</em> drifted, not on every block change, so it stays a meaningful
     * signal rather than per-tick noise.</p>
     */
    default void onExternalMassChange() {
    }
}
