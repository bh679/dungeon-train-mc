package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import games.brennan.dungeontrain.ship.sable.PhysicsFreeze;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Issue #646 — the <b>true</b> native boundary. Every native Rapier call funnels through the
 * pipeline impl {@code RapierPhysicsPipeline}: {@code RigidBodyHandle} delegates here, and callers
 * <em>outside</em> {@code SubLevelPhysicsSystem} reach it directly — e.g. the per-tick
 * {@code PhysicsChunkTicketManager.update} calls {@code pipeline.getLinearVelocity(body, out)}, which
 * bypasses both the handle and the SubLevelPhysicsSystem method gates and panics on a DT-frozen
 * (removed) body (exit 134). Gating the pipeline's per-body reads/writes catches <em>all</em> such
 * callers by construction.
 *
 * <p>For a frozen body: velocity reads return zero, pose read returns the caller's buffer unchanged,
 * and velocity/impulse/teleport writes no-op. {@code add}/{@code remove} are deliberately NOT gated —
 * they are the freeze/unfreeze primitives themselves, and {@link PhysicsFreeze} runs them with the
 * flag cleared so this mixin never blocks them.
 *
 * <p>String target + {@code remap = false}: {@code RapierPhysicsPipeline} ships in Sable's jar-in-jar
 * and is not on DT's compile classpath (same pattern as the Vivecraft mixin). Its method args
 * ({@link PhysicsPipelineBody}, {@link ServerSubLevel}, JOML, {@link Pose3d}) are on the classpath, so
 * the handlers still compile. <b>Re-audit this method set on any {@code sable_version} bump.</b></p>
 */
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class RapierPipelineFreezeMixin {

    private static boolean dungeonTrain$frozen(Object body) {
        return body instanceof ServerSubLevel sl && PhysicsFreeze.isFrozen(sl);
    }

    @Inject(method = "getLinearVelocity", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenLinVel(PhysicsPipelineBody body, Vector3d out, CallbackInfoReturnable<Vector3d> cir) {
        if (dungeonTrain$frozen(body)) cir.setReturnValue(out.set(0.0, 0.0, 0.0));
    }

    @Inject(method = "getAngularVelocity", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenAngVel(PhysicsPipelineBody body, Vector3d out, CallbackInfoReturnable<Vector3d> cir) {
        if (dungeonTrain$frozen(body)) cir.setReturnValue(out.set(0.0, 0.0, 0.0));
    }

    @Inject(method = "readPose", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenReadPose(ServerSubLevel subLevel, Pose3d out, CallbackInfoReturnable<Pose3d> cir) {
        if (dungeonTrain$frozen(subLevel)) cir.setReturnValue(out); // no native read; last-known pose stands
    }

    @Inject(method = "teleport", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenTeleport(PhysicsPipelineBody body, Vector3dc position, Quaterniondc rotation, CallbackInfo ci) {
        if (dungeonTrain$frozen(body)) ci.cancel();
    }

    @Inject(method = "addLinearAndAngularVelocity", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenAddVel(PhysicsPipelineBody body, Vector3dc linear, Vector3dc angular, CallbackInfo ci) {
        if (dungeonTrain$frozen(body)) ci.cancel();
    }

    @Inject(method = "applyImpulse", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenApplyImpulse(PhysicsPipelineBody body, Vector3dc a, Vector3dc b, CallbackInfo ci) {
        if (dungeonTrain$frozen(body)) ci.cancel();
    }

    @Inject(method = "applyLinearAndAngularImpulse", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenApplyLinAngImpulse(PhysicsPipelineBody body, Vector3dc linear, Vector3dc angular, boolean wake, CallbackInfo ci) {
        if (dungeonTrain$frozen(body)) ci.cancel();
    }
}
