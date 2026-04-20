package games.brennan.dungeontrain.train;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
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

    // Proportional gain on linear velocity error — snappier vs smoother.
    private static final double KP_LINEAR = 5.0;
    // Proportional gain on angular velocity — drives omega toward zero
    // (train must not rotate). Separate from linear so we can tune.
    private static final double KP_ANGULAR = 20.0;
    // Minecraft surface gravity (m/s²) — VS ships feel real gravity
    // unless compensated; keep the train level with mass·g upward.
    private static final double GRAVITY = 9.81;

    @JsonIgnore
    private static final Logger LOGGER = LogUtils.getLogger();

    private double targetX;
    private double targetY;
    private double targetZ;

    @JsonIgnore
    private transient long tickCount = 0;

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
        Vector3dc vel = physShip.getVelocity();
        Vector3dc omega = physShip.getAngularVelocity();
        double mass = physShip.getMass();

        // Linear: drive world-space velocity toward target, cancel gravity.
        // applyInvariantForce applies at center of mass (no torque arm) —
        // using applyWorldForce with pos=(0,0,0) would apply force at world
        // origin and spin the ship away on every tick.
        double fx = (targetX - vel.x()) * mass * KP_LINEAR;
        double fy = (targetY - vel.y()) * mass * KP_LINEAR + mass * GRAVITY;
        double fz = (targetZ - vel.z()) * mass * KP_LINEAR;
        physShip.applyInvariantForce(new Vector3d(fx, fy, fz));

        // Angular: drive omega → 0. Train must not roll, pitch, or yaw.
        // Torque units are mass·length²/time² (moment of inertia × ang accel);
        // mass is a rough stand-in, tuned via KP_ANGULAR.
        double tx = -omega.x() * mass * KP_ANGULAR;
        double ty = -omega.y() * mass * KP_ANGULAR;
        double tz = -omega.z() * mass * KP_ANGULAR;
        physShip.applyInvariantTorque(new Vector3d(tx, ty, tz));

        if ((++tickCount) % 60 == 0) {
            LOGGER.info(
                "[DungeonTrain] physTick #{} vel=({},{},{}) omega=({},{},{}) mass={}",
                tickCount, vel.x(), vel.y(), vel.z(), omega.x(), omega.y(), omega.z(), mass
            );
        }
    }
}
