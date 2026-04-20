package games.brennan.dungeontrain.train;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.ships.ShipPhysicsListener;
import org.valkyrienskies.core.api.world.PhysLevel;

/**
 * Drives a VS ship toward a fixed world-space velocity every physics
 * tick. Runs on the physics thread — mutable state kept to a minimum.
 *
 * Uses {@link ShipPhysicsListener#physTick} (VS 2.4+ API).
 * MVP: non-persistent. Ship loses this listener on world reload.
 */
@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.ANY,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE
)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TrainForcesInducer implements ShipPhysicsListener {

    // Proportional gain on velocity error — snappier vs smoother.
    private static final double KP = 1.0;
    // Minecraft surface gravity (m/s²) — VS ships feel real gravity
    // unless compensated; keep the train level with mass·g upward.
    private static final double GRAVITY = 9.81;

    private double targetX;
    private double targetY;
    private double targetZ;

    @SuppressWarnings("unused")
    public TrainForcesInducer() {
        this(new Vector3d());
    }

    public TrainForcesInducer(Vector3dc targetVelocity) {
        this.targetX = targetVelocity.x();
        this.targetY = targetVelocity.y();
        this.targetZ = targetVelocity.z();
    }

    @Override
    public void physTick(@NotNull PhysShip physShip, @NotNull PhysLevel physLevel) {
        Vector3dc currentVel = physShip.getVelocity();
        double mass = physShip.getMass();

        double fx = (targetX - currentVel.x()) * mass * KP;
        double fy = (targetY - currentVel.y()) * mass * KP + mass * GRAVITY;
        double fz = (targetZ - currentVel.z()) * mass * KP;

        physShip.applyWorldForce(new Vector3d(fx, fy, fz), new Vector3d());
    }
}
