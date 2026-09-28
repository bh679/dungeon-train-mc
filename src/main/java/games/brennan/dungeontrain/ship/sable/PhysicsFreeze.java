package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

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
 * <p><b>The body parks; the pose does not.</b> Only the native Rapier body is left at rest. The
 * sub-level's Java {@code logicalPose()} keeps following the kinematic driver every tick via
 * {@link #followParked} — a plain field write, no native call — so everything that reads the pose
 * sees the carriage where the train actually has it: Sable's tracking system (which decides from
 * {@code logicalPose().position()} whether a player is close enough to start tracking it), its
 * chunk tickets and entity collision, serialization, and DT's own {@code worldAABB()} consumers
 * (near/hold windows, the {@code [seamgap]} trace, contents despawn/restore). Without this the pose
 * stayed at the parked spot while the group's true slot moved away at train speed, so a group whose
 * slot the player was standing in was never re-tracked and never unfroze — a group-sized hole in the
 * train, with the group itself stacked hundreds of blocks back on top of other parked groups.</p>
 *
 * <p><b>Park-at-rest on freeze.</b> A parked kinematic body must have zero velocity, or the native step
 * drifts it. On freeze we do one final {@link #parkAtRest} pass (teleport to the authoritative
 * {@code logicalPose} + zero linear/angular velocity) <em>before</em> setting the flag, so the reader
 * mixins don't cancel that final teleport/velocity write. On unfreeze {@code applyTickOutput} resumes
 * and teleports the body onto the pose that has been following the train all along — no jump.</p>
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
    public static void freeze(ServerSubLevel sl, long gameTick) {
        if (!(sl instanceof DtFreezable flag)) return;
        if (flag.dt$isPhysicsFrozen()) return;
        parkAtRest(sl);                  // final teleport + zero velocity while the flag is still clear (ungated)
        Vector3dc parkedAt = sl.logicalPose().position();
        flag.dt$setParked(parkedAt.x(), parkedAt.y(), parkedAt.z(), gameTick);
        flag.dt$setPhysicsFrozen(true);  // readers skip from here; applyTickOutput parks it
    }

    /** Clear the frozen flag; {@link SableManagedShip#applyTickOutput} resumes teleporting next tick. Idempotent. */
    public static void unfreeze(ServerSubLevel sl) {
        if (sl instanceof DtFreezable flag) flag.dt$setPhysicsFrozen(false);
    }

    /**
     * Keep a parked carriage's <em>pose</em> on the train. Called by
     * {@link SableManagedShip#applyTickOutput} every tick while frozen, in place of the native
     * teleport: writes the driver's position/rotation straight into the sub-level's Java
     * {@code logicalPose()} and refreshes its world bounding box from it. No {@code RigidBodyHandle}
     * call is made — the native body stays parked, which is the whole soft-freeze saving.
     *
     * <p>Safe against Sable's own writers: the only thing that writes {@code logicalPose()} from the
     * native body is {@code SubLevelPhysicsSystem.updatePose}, which the freeze mixin cancels for a
     * frozen sub-level, and Sable's per-tick {@code updateLastPose()}/{@code updateBoundingBox()}
     * both <em>read</em> this pose. Sable's entity collision and tracking read it too, so entities
     * and clients meet the carriage at this position, not at the parked body.</p>
     */
    public static void followParked(ServerSubLevel sl, Vector3dc position, Quaterniondc rotation) {
        if (!(sl.logicalPose() instanceof Pose3d pose)) return;
        pose.position().set(position);
        pose.orientation().set(rotation);
        sl.updateBoundingBox();
    }

    /**
     * How far the parked body sits behind the pose right now, in blocks — the distance the
     * resume teleport will move it. Only meaningful while frozen ({@code 0} otherwise).
     */
    public static double bodyLagBlocks(ServerSubLevel sl) {
        if (!(sl instanceof DtFreezable f) || !f.dt$isPhysicsFrozen()) return 0.0;
        return sl.logicalPose().position().distance(f.dt$parkedX(), f.dt$parkedY(), f.dt$parkedZ());
    }

    /** Ticks this sub-level has been parked for, or {@code 0} if it is not frozen. */
    public static long parkedTicks(ServerSubLevel sl, long gameTick) {
        if (!(sl instanceof DtFreezable f) || !f.dt$isPhysicsFrozen() || f.dt$parkedGameTick() < 0) return 0L;
        return Math.max(0L, gameTick - f.dt$parkedGameTick());
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
