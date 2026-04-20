package games.brennan.dungeontrain.train;

import org.jetbrains.annotations.NotNull;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.bodies.properties.BodyTransform;
import org.valkyrienskies.core.api.ships.ServerShipTransformProvider;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;

/**
 * Drives a VS ship at a fixed world-space velocity by prescribing its next
 * transform every physics tick — a kinematic alternative to force-based
 * physics that bypasses the VS/Bullet mass threshold we hit with
 * {@link TrainForcesInducer} at count=20.
 *
 * Entity ride-along still works: VS uses the returned {@code nextVel} to
 * compute carry-along for entities standing on the ship's blocks.
 *
 * This class is used as the DUngeon Train marker — a loaded ship whose
 * transform provider is a {@code TrainTransformProvider} is one of ours.
 */
public final class TrainTransformProvider implements ServerShipTransformProvider {

    // VS physics thread runs at 60 Hz; each call to provideNextTransformAndVelocity
    // represents one physics step.
    private static final double PHYSICS_DT = 1.0 / 60.0;
    private static final Vector3dc ZERO_OMEGA = new Vector3d();

    private final Vector3d targetVelocity;

    public TrainTransformProvider(Vector3dc targetVelocity) {
        this.targetVelocity = new Vector3d(targetVelocity);
    }

    public Vector3dc getTargetVelocity() {
        return targetVelocity;
    }

    @NotNull
    @Override
    public NextTransformAndVelocityData provideNextTransformAndVelocity(
        @NotNull ShipTransform prev,
        @NotNull ShipTransform current
    ) {
        Vector3dc currentPos = current.getPosition();
        Vector3d nextPos = new Vector3d(currentPos).add(
            targetVelocity.x() * PHYSICS_DT,
            targetVelocity.y() * PHYSICS_DT,
            targetVelocity.z() * PHYSICS_DT
        );
        BodyTransform nextTransform = current.toBuilder().position(nextPos).build();
        return new NextTransformAndVelocityData(nextTransform, targetVelocity, ZERO_OMEGA);
    }
}
