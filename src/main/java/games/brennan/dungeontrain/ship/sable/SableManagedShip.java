package games.brennan.dungeontrain.ship.sable;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
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
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sable adapter for {@link ManagedShip}. Wraps a {@link ServerSubLevel} and
 * forwards transform queries through {@link Pose3dc#transformPosition}.
 *
 * <p>Kinematic application: the train code (specifically
 * {@link games.brennan.dungeontrain.train.TrainWindowManager}) already calls
 * {@link #applyTickOutput} per tick, so no separate ticker is needed.
 * {@link #applyTickOutput} resets velocity then teleports + sets velocity on
 * Sable's {@link RigidBodyHandle}.</p>
 *
 * <p>{@link #setStatic} is a no-op for Sable: the per-tick teleport+velocity
 * pattern subsumes "static" behaviour — a kinematic driver can return zero
 * velocity to keep the body parked, and the teleport corrects any physics
 * drift each tick.</p>
 */
public final class SableManagedShip implements ManagedShip {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Sable can re-create a {@link ServerSubLevel} between assembly and the
     * first server tick (the integrated server saves + loads the freshly
     * assembled sub-level on world create), which gives us a different
     * reference than the one we set the driver on. The {@link SableShipyard}
     * wrapper map is keyed on those references and so creates a fresh
     * wrapper without our driver. Pinning kinematic drivers in a static map
     * by stable {@link SubLevel#getUniqueId()} survives that re-creation —
     * any wrapper for the same UUID picks the driver back up.
     */
    private static final java.util.Map<UUID, KinematicDriver> DRIVERS_BY_UUID = new ConcurrentHashMap<>();

    private final ServerSubLevel subLevel;

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
    public UUID subLevelId() {
        return subLevel.getUniqueId();
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
    public boolean isResident() {
        // A removed sub-level may still answer boundingBox()/logicalPose() with
        // a stale last-known pose, so registry-edge reference resolution must
        // treat it as non-resident and reload from holding instead.
        return !subLevel.isRemoved();
    }

    @Override
    public boolean hasSerializationPointer() {
        // Sable sets this only once the sub-level has been written to disk (an
        // autosave / chunk save). Until then a cull yields a null-pointer
        // holding entry that snatchAndLoad can't revive — so DT holds the group
        // force-loaded until this is non-null. See ManagedShip#hasSerializationPointer.
        return subLevel.getLastSerializationPointer() != null;
    }

    @Override
    @Nullable
    public KinematicDriver getKinematicDriver() {
        UUID id = subLevel.getUniqueId();
        KinematicDriver d = DRIVERS_BY_UUID.get(id);
        if (d != null) return d;
        // Sable's FloatingBlockController can split a sub-level into pieces
        // (e.g. when carriages aren't fully connected by blocks). The split
        // pieces inherit the original sub-level's UUID via getSplitFromSubLevel().
        // Walk that chain to find the driver of the originally-driven sub-level
        // and cache it locally so subsequent lookups skip the chain walk.
        UUID origin = subLevel.getSplitFromSubLevel();
        while (origin != null && d == null) {
            d = DRIVERS_BY_UUID.get(origin);
            if (d != null) break;
            // Defensive bound — Sable splits should never chain more than a
            // handful of times; cap at 8 to avoid pathological infinite loops
            // if an origin chain ever cycles.
            origin = null;
        }
        if (d != null) DRIVERS_BY_UUID.put(id, d);
        return d;
    }

    @Override
    public void setKinematicDriver(KinematicDriver driver) {
        UUID id = subLevel.getUniqueId();
        if (driver == null) {
            DRIVERS_BY_UUID.remove(id);
        } else {
            DRIVERS_BY_UUID.put(id, driver);
        }
    }

    @Override
    public void setStatic(boolean isStatic) {
        // No-op for Sable. The kinematic-driver pattern (per-tick teleport +
        // velocity reset in applyTickOutput) implicitly handles "static":
        // a driver returning zero velocity + the same position each tick
        // keeps the body parked.
    }

    @Override
    public void applyTickOutput(KinematicDriver.TickOutput output) {
        // A DT-frozen carriage (#646) has been pipeline.remove()d from the physics scene but is
        // still LOADED, so RigidBodyHandle.isValid() — which only checks isRemoved() — still reports
        // true. Reading its velocity below (getLinearVelocity) would panic native Rapier on the
        // freed body (exit 134). This is DT's own per-tick native reader, distinct from the Sable
        // readers gated by the *FreezeMixin classes; it must be gated here too. The frozen body is
        // re-added on unfreeze and resumes teleporting the next tick.
        if (PhysicsFreeze.isFrozen(subLevel)) {
            return;
        }

        RigidBodyHandle handle = RigidBodyHandle.of(subLevel);
        if (handle == null || !handle.isValid()) {
            LOGGER.trace("[Sable] applyTickOutput: handle invalid for sub-level {}",
                subLevel.getUniqueId());
            return;
        }

        // Teleport overrides the body's pose authoritatively. Sable's renderer
        // interpolates between lastPose (set during the just-completed tick)
        // and the current pose, so the visual motion is smooth as long as
        // applyTickOutput fires every tick.
        handle.teleport(output.position(), output.rotation());

        // Pin the model-space pivot ({@code Pose3d.rotationPoint}) to the
        // driver's spawn-time-locked value. Sable's MassTracker recomputes
        // the sub-level's centre of mass on every block change and feeds it
        // back into rotationPoint, which would otherwise translate every
        // block visibly each time the appender adds a carriage (the world
        // mapping is {@code position + rotation*(voxel_shipyard - rotationPoint)},
        // so any rotationPoint drift shifts ALL blocks). RigidBodyHandle
        // exposes no setter for rotationPoint, but Pose3d returns the live
        // mutable {@code Vector3d}, so {@code .set(...)} pins it in place.
        // This is the Sable equivalent of the VS-era inertia-pin we
        // formerly applied via reflection in {@code ship.vs.VsInertiaLocker}.
        Vector3dc pivotTarget = output.positionInModel();
        if (pivotTarget != null && subLevel.logicalPose() instanceof Pose3d livePose) {
            livePose.rotationPoint().set(pivotTarget.x(), pivotTarget.y(), pivotTarget.z());
        }

        // Reset velocity to zero, then add the driver's chosen velocity.
        // Sable has no direct `setVelocity` — only `addLinearAndAngularVelocity`
        // — so we negate the current velocity first.
        Vector3d curLin = new Vector3d();
        Vector3d curAng = new Vector3d();
        handle.getLinearVelocity(curLin);
        handle.getAngularVelocity(curAng);
        handle.addLinearAndAngularVelocity(curLin.negate(), curAng.negate());
        handle.addLinearAndAngularVelocity(output.linearVelocity(), output.angularVelocity());

        // Pure-kinematic enforcement: wipe any queued forces (gravity, drag,
        // lift, propulsion) that Sable's per-frame providers scheduled for
        // this body before the next physics tick reads them. With this and
        // the teleport above, no impulse can leak through to push or tilt
        // the train between server ticks. The map is null until Sable lazily
        // creates it on first force queueing — null means nothing to clear.
        java.util.Map<ForceGroup, QueuedForceGroup> queued = subLevel.getQueuedForceGroups();
        if (queued != null) {
            for (QueuedForceGroup group : queued.values()) {
                group.reset();
            }
        }

        // Mirror our kinematic intent into the sublevel's networked velocity
        // fields so clients carry the prescribed motion. These are public
        // final Vector3d's — references stay; only contents change.
        //
        // Gate the write on there being at least one tracking client. A
        // carriage force-held resident (or catching up after a holding
        // reload) with no client in range would otherwise have these velocity
        // fields networked to nobody — and Sable logs "Received a sub-level
        // movement packet for a non-existent sub-level" when a client that has
        // culled the sub-level receives that stale movement. The server-side
        // teleport above still runs unconditionally, so a client that
        // re-enters tracking range gets a correct full-sync pose from Sable's
        // own tracking system — only the redundant, desync-triggering velocity
        // packet to non-tracking clients is suppressed.
        if (!subLevel.getTrackingPlayers().isEmpty()) {
            subLevel.latestLinearVelocity.set(output.linearVelocity());
            subLevel.latestAngularVelocity.set(output.angularVelocity());
        }
    }

    @Override
    @Nullable
    public InertiaSnapshot captureInertia() {
        MassData mass = subLevel.getMassTracker();
        if (mass.isInvalid()) {
            return null;
        }
        Vector3dc com = mass.getCenterOfMass();
        if (com == null) {
            // Empty / invalid mass tracker — no blocks contributed to mass.
            return null;
        }
        return new InertiaSnapshot(com, mass.getMass(), mass.getInertiaTensor());
    }

    @Override
    public void restoreInertia(@Nullable InertiaSnapshot snapshot) {
        // Sable's MassTracker is read-only; restore is intentionally a no-op.
        // The pivot-drift problem this API protected against in VS does not
        // exist in Sable's kinematic mode (block mutations don't recompute
        // physics state mid-tick). See plans/modular-scribbling-thunder.md.
    }
}
