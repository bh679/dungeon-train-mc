package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import games.brennan.dungeontrain.ship.sable.PhysicsFreeze;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Issue #646 — the <b>native boundary</b> gate. Every per-body native Rapier read/write funnels
 * through {@link RigidBodyHandle}, so gating here makes a DT-frozen carriage safe for <em>any</em>
 * caller — including ones outside {@code SubLevelPhysicsSystem} that an enumeration of that class
 * alone misses (found the hard way: {@code SableManagedShip.applyTickOutput} and, per-tick,
 * {@code PhysicsChunkTicketManager.update}, both call {@code handle.getLinearVelocity} on every
 * resident sub-level → native panic on a removed body, exit 134).
 *
 * <p>For a frozen body: velocity reads return zero, force/teleport/velocity writes no-op, and
 * {@code isValid()} reports false (so Sable's own {@code isValid()} guards skip it too). This is
 * the belt-and-suspenders complement to the method-level gates in {@link ServerSubLevelFreezeMixin}
 * / {@link SubLevelPhysicsSystemFreezeMixin}; those skip the wasteful physics work, this guarantees
 * no native handle is ever touched. {@code remap = false}: Sable's own names.
 *
 * <p><b>Re-audit on any {@code sable_version} bump</b> — a new native-reaching handle method must be
 * added here.</p>
 */
@Mixin(value = RigidBodyHandle.class, remap = false)
public abstract class RigidBodyHandleFreezeMixin {

    @Shadow @Final private PhysicsPipelineBody body;

    @Unique
    private boolean dungeonTrain$bodyFrozen() {
        return body instanceof ServerSubLevel sl && PhysicsFreeze.isFrozen(sl);
    }

    @Inject(method = "isValid()Z", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenIsInvalid(CallbackInfoReturnable<Boolean> cir) {
        if (dungeonTrain$bodyFrozen()) cir.setReturnValue(false);
    }

    @Inject(method = "getLinearVelocity()Lorg/joml/Vector3dc;", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenLinVel(CallbackInfoReturnable<Vector3dc> cir) {
        if (dungeonTrain$bodyFrozen()) cir.setReturnValue(new Vector3d());
    }

    @Inject(method = "getAngularVelocity()Lorg/joml/Vector3dc;", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenAngVel(CallbackInfoReturnable<Vector3dc> cir) {
        if (dungeonTrain$bodyFrozen()) cir.setReturnValue(new Vector3d());
    }

    @Inject(method = "getLinearVelocity(Lorg/joml/Vector3d;)Lorg/joml/Vector3d;", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenLinVelOut(Vector3d out, CallbackInfoReturnable<Vector3d> cir) {
        if (dungeonTrain$bodyFrozen()) cir.setReturnValue(out.set(0.0, 0.0, 0.0));
    }

    @Inject(method = "getAngularVelocity(Lorg/joml/Vector3d;)Lorg/joml/Vector3d;", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenAngVelOut(Vector3d out, CallbackInfoReturnable<Vector3d> cir) {
        if (dungeonTrain$bodyFrozen()) cir.setReturnValue(out.set(0.0, 0.0, 0.0));
    }

    @Inject(method = "applyForcesAndReset", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenNoForce(ForceTotal forces, CallbackInfo ci) {
        if (dungeonTrain$bodyFrozen()) ci.cancel();
    }

    @Inject(method = "addLinearAndAngularVelocity", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenNoAddVel(Vector3dc linear, Vector3dc angular, CallbackInfo ci) {
        if (dungeonTrain$bodyFrozen()) ci.cancel();
    }

    @Inject(method = "teleport", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenNoTeleport(Vector3dc position, Quaterniondc rotation, CallbackInfo ci) {
        if (dungeonTrain$bodyFrozen()) ci.cancel();
    }
}
