package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import games.brennan.dungeontrain.ship.InertiaSnapshot;
import games.brennan.dungeontrain.ship.KinematicDriver;
import games.brennan.dungeontrain.ship.ManagedShip;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;

/**
 * Sable adapter for {@link ManagedShip}. Wraps a {@link ServerSubLevel} and
 * forwards transform queries through {@link Pose3dc#transformPosition}.
 *
 * <p>Phase 2 chunk 2 — skeleton only. Kinematic-driver wiring and inertia
 * capture live in chunks 3 and 4 respectively. Until then,
 * {@link #setKinematicDriver}, {@link #applyTickOutput}, {@link #setStatic},
 * and {@link #captureInertia} are no-ops with diagnostic logging.</p>
 */
public final class SableManagedShip implements ManagedShip {

    private final ServerSubLevel subLevel;

    @Nullable
    private KinematicDriver kinematicDriver;

    public SableManagedShip(ServerSubLevel subLevel) {
        this.subLevel = subLevel;
    }

    /** Internal accessor for {@link SableShipyard} (and the kinematic ticker once chunk 3 lands). */
    public ServerSubLevel subLevel() {
        return subLevel;
    }

    @Override
    public long id() {
        // UUID's most-significant 64 bits — collision-resistant enough for
        // per-level use (we only compare ids against ships in the same level).
        return subLevel.getUniqueId().getMostSignificantBits();
    }

    @Override
    public Vector3d worldToShip(Vector3d worldPos) {
        return subLevel.logicalPose().transformPositionInverse(worldPos);
    }

    @Override
    public Vector3d shipToWorld(Vector3d modelPos) {
        return subLevel.logicalPose().transformPosition(modelPos);
    }

    @Override
    public Vector3dc currentWorldPosition() {
        return subLevel.logicalPose().position();
    }

    @Override
    public Quaterniondc currentRotation() {
        return subLevel.logicalPose().orientation();
    }

    @Override
    public Vector3dc currentPositionInModel() {
        return subLevel.logicalPose().rotationPoint();
    }

    @Override
    public AABBdc worldAABB() {
        BoundingBox3dc b = subLevel.boundingBox();
        return new AABBd(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    @Override
    @Nullable
    public KinematicDriver getKinematicDriver() {
        return kinematicDriver;
    }

    @Override
    public void setKinematicDriver(KinematicDriver driver) {
        this.kinematicDriver = driver;
        // Per-tick application of the driver's output happens in
        // SableKinematicTicker, landing in chunk 3 of the Phase 2 plan.
    }

    @Override
    public void setStatic(boolean isStatic) {
        // Phase 2 chunk 3 — wires through to PhysicsPipeline kinematic flag.
    }

    @Override
    public void applyTickOutput(KinematicDriver.TickOutput output) {
        // Phase 2 chunk 3 — wires through to PhysicsPipeline.teleport +
        // addLinearAndAngularVelocity. Until then this is a no-op so the
        // train stays parked but the rest of the world doesn't crash.
    }

    @Override
    @Nullable
    public InertiaSnapshot captureInertia() {
        // Phase 2 chunk 4 — best-effort read from MassTracker.
        return null;
    }

    @Override
    public void restoreInertia(@Nullable InertiaSnapshot snapshot) {
        // Sable's MassTracker is read-only; restore is intentionally a no-op.
        // The pivot-drift problem this API protected against in VS does not
        // exist in Sable's kinematic mode (block mutations don't recompute
        // physics state mid-tick). See plans/modular-scribbling-thunder.md.
    }
}
