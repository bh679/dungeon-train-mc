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
 * Issue #646 (soft-freeze) — the per-body native boundary. Every native Rapier call funnels through
 * the pipeline impl {@code RapierPhysicsPipeline}: {@code RigidBodyHandle} delegates here, and callers
 * <em>outside</em> {@code SubLevelPhysicsSystem} reach it directly (e.g. the per-tick
 * {@code PhysicsChunkTicketManager.update} calls {@code pipeline.getLinearVelocity(body, out)}).
 * Gating the pipeline's per-body reads/writes for a DT-frozen carriage skips Sable's per-body work for
 * <em>all</em> such callers by construction — and that skipped work is the soft-freeze saving.
 *
 * <p>Soft-freeze leaves the body IN the scene (nothing is removed), so the <em>freeze</em> gates are a
 * pure optimisation — they cannot abort. For a frozen (parked) body: velocity reads return zero (it is
 * genuinely parked), pose read returns the caller's buffer unchanged (its last-known frozen pose stands),
 * and velocity/impulse/teleport/stat writes no-op so nothing can nudge the parked body.
 * {@link PhysicsFreeze#freeze} runs its one-time park pass with the flag still clear, so this mixin never
 * blocks that final teleport/velocity write.
 *
 * <p>The two velocity-<em>read</em> handlers ({@code getLinearVelocity}/{@code getAngularVelocity})
 * additionally double as a <b>removed-body crash guard</b>: they return zero for a body Sable has already
 * removed (see {@link #dungeonTrain$parkedForRead}), turning the {@code assertBodyValid}
 * "Body has been removed" abort into a benign parked read. This is independent of soft-freeze and must be
 * preserved on any {@code sable_version} re-audit.
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
     * A body reads as parked (zero velocity) when it is either DT soft-frozen (genuinely at rest) or
     * already removed from Sable's Rapier scene. A removed body makes {@code assertBodyValid} throw
     * {@code RuntimeException("Body has been removed")}: Sable's own {@code ActiveSableCompanion#getVelocity}
     * can query a stale {@code RigidBodyHandle} during an entity tick (livestock standing near a ship) after
     * an async cull removes the sub-level's body, crashing the server thread. Returning zero here mirrors
     * Sable's own {@code getContaining == null → Vector3d.zero()} fallback and is the correct relative
     * velocity for a ship that no longer exists. Null-safe, though {@code assertBodyValid} would itself NPE
     * on a null body, so null is not a real runtime scenario.
     */
    private static boolean dungeonTrain$parkedForRead(PhysicsPipelineBody body) {
        return dungeonTrain$frozen(body) || (body != null && body.isRemoved());
    }

    @Inject(method = "getLinearVelocity", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenLinVel(PhysicsPipelineBody body, Vector3d out, CallbackInfoReturnable<Vector3d> cir) {
        if (dungeonTrain$parkedForRead(body)) cir.setReturnValue(out.set(0.0, 0.0, 0.0));
    }

    @Inject(method = "getAngularVelocity", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$frozenAngVel(PhysicsPipelineBody body, Vector3d out, CallbackInfoReturnable<Vector3d> cir) {
        if (dungeonTrain$parkedForRead(body)) cir.setReturnValue(out.set(0.0, 0.0, 0.0));
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
