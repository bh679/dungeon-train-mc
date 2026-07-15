package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
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
 * Issue #646 (soft-freeze) — the per-body native boundary. Every native Rapier call funnels through
 * the pipeline impl {@code RapierPhysicsPipeline}: {@code RigidBodyHandle} delegates here, and callers
 * <em>outside</em> {@code SubLevelPhysicsSystem} reach it directly (e.g. the per-tick
 * {@code PhysicsChunkTicketManager.update} calls {@code pipeline.getLinearVelocity(body, out)}).
 * Gating the pipeline's per-body reads/writes for a DT-frozen carriage skips Sable's per-body work for
 * <em>all</em> such callers by construction — and that skipped work is the soft-freeze saving.
 *
 * <p>Soft-freeze leaves the body IN the scene (nothing is removed), so these gates are a pure
 * optimisation, not a crash guard — they cannot abort. For a frozen (parked) body: velocity reads
 * return zero (it is genuinely parked), pose read returns the caller's buffer unchanged (its
 * last-known frozen pose stands), and velocity/impulse/teleport/stat writes no-op so nothing can nudge
 * the parked body. {@link PhysicsFreeze#freeze} runs its one-time park pass with the flag still clear,
 * so this mixin never blocks that final teleport/velocity write.
 *
 * <p><b>Removed-body read guard</b> (separate from soft-freeze): the three read gates also short-circuit
 * when the body has been <em>removed</em> from the scene (handle gone/invalid, via
 * {@link #dungeonTrain$bodyRemoved}). A stale caller — e.g. a passenger's {@code ActiveSableCompanion}
 * still ticking after its sub-level unloaded during a long teleport — would otherwise read the dead body
 * and hit Sable's {@code assertBodyValid} ("Body has been removed"), crashing the ticking entity. This IS
 * a crash guard, but only on the read path and only via the public {@code isValid()} check.</p>
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

    /**
     * True when {@code body}'s Rapier rigid body has been REMOVED from the scene (its handle is gone or
     * invalid) — distinct from a soft-frozen body, which stays in-scene. Reading velocity/pose off a
     * removed body makes Sable's {@code assertBodyValid} throw {@code "Body has been removed"}, crashing
     * whatever is mid-tick (e.g. a train passenger's {@code ActiveSableCompanion} after its sub-level
     * unloaded during a long teleport). Checked via the public {@link RigidBodyHandle#of}/{@code isValid()}
     * — the same guard {@link PhysicsFreeze} uses — so it never touches the native call that would abort.
     */
    private static boolean dungeonTrain$bodyRemoved(Object body) {
        if (!(body instanceof ServerSubLevel sl)) return false;
        RigidBodyHandle handle = RigidBodyHandle.of(sl);
        return handle == null || !handle.isValid();
    }

    @Inject(method = "getLinearVelocity", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenLinVel(PhysicsPipelineBody body, Vector3d out, CallbackInfoReturnable<Vector3d> cir) {
        if (dungeonTrain$frozen(body) || dungeonTrain$bodyRemoved(body)) cir.setReturnValue(out.set(0.0, 0.0, 0.0));
    }

    @Inject(method = "getAngularVelocity", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenAngVel(PhysicsPipelineBody body, Vector3d out, CallbackInfoReturnable<Vector3d> cir) {
        if (dungeonTrain$frozen(body) || dungeonTrain$bodyRemoved(body)) cir.setReturnValue(out.set(0.0, 0.0, 0.0));
    }

    @Inject(method = "readPose", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenReadPose(ServerSubLevel subLevel, Pose3d out, CallbackInfoReturnable<Pose3d> cir) {
        // frozen: last-known pose stands; removed: skip the dead-body native read that would abort.
        if (dungeonTrain$frozen(subLevel) || dungeonTrain$bodyRemoved(subLevel)) cir.setReturnValue(out);
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

    // Pushes centre-of-mass / mass / bounds to the native body (setCenterOfMass et al.) on a stat
    // change — e.g. world-load mass settling. Skipping is correct for a parked body: its mass/pose are
    // frozen, and applyTickOutput re-establishes them natively the tick after it unfreezes.
    @Inject(method = "onStatsChanged", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenOnStatsChanged(ServerSubLevel subLevel, CallbackInfo ci) {
        if (dungeonTrain$frozen(subLevel)) ci.cancel();
    }

    @Inject(method = "wakeUp", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenWakeUp(PhysicsPipelineBody body, CallbackInfo ci) {
        if (dungeonTrain$frozen(body)) ci.cancel();
    }
}
