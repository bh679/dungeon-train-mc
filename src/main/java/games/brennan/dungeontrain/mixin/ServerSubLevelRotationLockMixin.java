package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import games.brennan.dungeontrain.ship.sable.PhysicsFreeze;
import games.brennan.dungeontrain.ship.sable.TrainRotationLock;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carriage rotation lock, native side — the third clamp, closing the gap the other two leave.
 *
 * <p>{@link SubLevelPhysicsSystemRotationLockMixin} flattens the pose Sable reads back and
 * {@link RapierPipelineRotationLockMixin} strips the angular half of every impulse that goes
 * <em>through the pipeline</em>. But Rapier's own contact solver — a player standing on the deck,
 * two carriages touching at a seam — generates angular velocity <em>inside</em> the native step,
 * with no pipeline call DT can see, and DT only negates it once per server tick in
 * {@code SableManagedShip.applyTickOutput}. Across the substeps in between the native body turns a
 * little about its centre of mass. Flattening the read-back orientation hides the turn but not its
 * side effect: the pivot is not the centre of mass, so a rotation about the centre of mass
 * <em>displaces the pivot</em>, and that comes back as a positional wobble proportional to the
 * pivot→COM offset — which is exactly what a player's build changes. Hence "the centre of mass
 * still causes jitter" even with the pivot itself frozen.
 *
 * <p>The fix is to never let native spin survive into a step: on {@code applyQueuedForces} — the
 * last per-body hook before {@code pipeline.physicsTick}, run once per substep — read the body's
 * angular velocity and cancel it. One native read per active locked body per substep, plus one
 * write only when there is spin to kill. A body that entered the step still can turn within that
 * one step from a contact resolved in it, but that no longer accumulates.
 *
 * <p>Skipped for a soft-frozen (parked) carriage: {@link ServerSubLevelFreezeMixin} cancels this
 * method for it and the body is at rest anyway, so there is nothing to read.</p>
 *
 * <p>{@code remap = false}: Sable's own names. Injection point bytecode-verified against
 * {@code sable-2.0.5+mc1.21.1}: {@code SubLevelPhysicsSystem.tickPipelinePhysics} calls
 * {@code prePhysicsTick} → {@code applyQueuedForces} → {@code pipeline.physicsTick(dt)} inside its
 * {@code substepsPerTick} loop. <b>Re-audit on any {@code sable_version} bump.</b></p>
 */
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class ServerSubLevelRotationLockMixin {

    /** Scratch for the native read. Physics runs on the server thread only, so one is enough. */
    @Unique private static final Vector3d DUNGEON_TRAIN$SPIN = new Vector3d();
    @Unique private static final Vector3d DUNGEON_TRAIN$NO_LINEAR = new Vector3d();

    @Inject(method = "applyQueuedForces", at = @At("HEAD"))
    private void dungeonTrain$killNativeSpin(SubLevelPhysicsSystem system, RigidBodyHandle handle,
                                             double dt, CallbackInfo ci) {
        ServerSubLevel self = (ServerSubLevel) (Object) this;
        if (!TrainRotationLock.isLocked(self) || PhysicsFreeze.isFrozen(self)) return;
        if (handle == null || !handle.isValid()) return;
        handle.getAngularVelocity(DUNGEON_TRAIN$SPIN);
        if (DUNGEON_TRAIN$SPIN.lengthSquared() == 0.0) return;
        handle.addLinearAndAngularVelocity(DUNGEON_TRAIN$NO_LINEAR, DUNGEON_TRAIN$SPIN.negate());
    }
}
