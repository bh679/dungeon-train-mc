package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Vector3d;

/**
 * <b>Soft-freeze</b> a single carriage's physics (issue #646) — flatten the ~O(bodies) cost of Sable
 * stepping every resident carriage, for carriages no client is watching. {@link PhysicsFreezeController}
 * decides <em>which</em> carriages; this class performs the freeze.
 *
 * <p><b>Soft, not hard.</b> Unlike the abandoned hard-freeze (which called {@code pipeline.remove()} to
 * drop the body — a Rust panic in Rapier aborts the JVM <em>uncatchably</em>, and DT can't race Sable's
 * async spawn/cull/recover for scene membership), soft-freeze <em>never mutates Sable's Rapier scene</em>.
 * The body stays IN the scene, valid and queryable. We just (a) set a per-instance flag so the reader
 * mixins skip Sable's per-body Java work for it ({@code prePhysicsTick}/{@code applyQueuedForces}/
 * {@code updatePose} + the pipeline read/write gates — that skipped work is the saving), and (b) stop DT
 * teleporting it in {@link SableManagedShip#applyTickOutput} (parks it). With nothing removed there is
 * <em>no native-abort surface</em> — it cannot crash.</p>
 *
 * <p><b>Park-at-rest on freeze.</b> A parked kinematic body must have zero velocity, or the native step
 * drifts it. On freeze we do one final {@link #parkAtRest} pass (teleport to the authoritative
 * {@code logicalPose} + zero linear/angular velocity) <em>before</em> setting the flag, so the reader
 * mixins don't cancel that final teleport/velocity write. On unfreeze {@code applyTickOutput} resumes
 * and re-teleports to the driver's pose on the next tick.</p>
 */
public final class PhysicsFreeze {

    private PhysicsFreeze() {}

    /** True while this sub-level is DT-frozen (readers skip it; {@code applyTickOutput} stops teleporting it). */
    public static boolean isFrozen(ServerSubLevel sl) {
        return sl instanceof DtFreezable f && f.dt$isPhysicsFrozen();
    }

    /**
     * Soft-freeze {@code sl}: park its kinematic body at rest, then set the frozen flag. The body stays
     * in Sable's Rapier scene — nothing is removed, so there is no uncatchable-abort surface. Idempotent.
     */
    public static void freeze(ServerSubLevel sl) {
        if (!(sl instanceof DtFreezable flag)) return;
        if (flag.dt$isPhysicsFrozen()) return;
        parkAtRest(sl);                  // final teleport + zero velocity while the flag is still clear (ungated)
        flag.dt$setPhysicsFrozen(true);  // readers skip from here; applyTickOutput parks it
    }

    /** Clear the frozen flag; {@link SableManagedShip#applyTickOutput} resumes teleporting next tick. Idempotent. */
    public static void unfreeze(ServerSubLevel sl) {
        if (sl instanceof DtFreezable flag) flag.dt$setPhysicsFrozen(false);
    }

    /**
     * One final kinematic-park pass, mirroring {@link SableManagedShip#applyTickOutput}: teleport the body
     * to its authoritative {@code logicalPose} and zero linear+angular velocity so the native kinematic
     * step doesn't drift it while frozen. MUST run while the frozen flag is still clear — otherwise
     * {@code RapierPipelineFreezeMixin} cancels the teleport/velocity writes. No-op if the handle is gone.
     */
    private static void parkAtRest(ServerSubLevel sl) {
        RigidBodyHandle handle = RigidBodyHandle.of(sl);
        if (handle == null || !handle.isValid()) return;
        Pose3dc pose = sl.logicalPose();
        handle.teleport(pose.position(), pose.orientation());
        Vector3d curLin = new Vector3d();
        Vector3d curAng = new Vector3d();
        handle.getLinearVelocity(curLin);
        handle.getAngularVelocity(curAng);
        handle.addLinearAndAngularVelocity(curLin.negate(), curAng.negate());
    }

    /** Hysteresis counter accessors (stored on the sub-level via the mixin). */
    public static int inactiveTicks(ServerSubLevel sl) {
        return sl instanceof DtFreezable f ? f.dt$inactiveTicks() : 0;
    }

    public static void setInactiveTicks(ServerSubLevel sl, int ticks) {
        if (sl instanceof DtFreezable f) f.dt$setInactiveTicks(ticks);
    }
}
