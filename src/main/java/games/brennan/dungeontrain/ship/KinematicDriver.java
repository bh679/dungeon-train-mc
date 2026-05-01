package games.brennan.dungeontrain.ship;

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
}
