package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import games.brennan.dungeontrain.ship.sable.ColliderBatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Runs every native physics step inside a forced {@link ColliderBatch} scope, so a block a collision
 * callback changes mid-step reaches Rapier only after the step has returned.
 *
 * <p><b>The freeze it prevents.</b> {@code Rapier3D.step} holds Rapier's world lock while it calls
 * back into Java. Sable's {@code FragileBlockCallback} breaks the glass, ice or leaves a carriage
 * hits from inside that callback; the break updates a neighbour's shape, Sable's
 * {@code handleBlockChange} pushes the change to {@code RapierPhysicsPipeline}, and when the new
 * state needs a voxel collider Rapier has not built yet, {@code Rapier3D.newVoxelCollider} takes the
 * same lock on the same thread — and waits on itself for ever (seen 2026-10-12: server thread parked
 * in {@code std RwLock::lock_contended} for 22+ minutes at 0% CPU). Inside the scope
 * {@link SubLevelPhysicsBlockChangeBatchMixin} records the section instead, and the scope's exit
 * re-uploads it whole once the lock is free.</p>
 *
 * <p>One wrap: the {@code INVOKEINTERFACE} of {@code PhysicsPipeline.physicsTick(D)V} in
 * {@code SubLevelPhysicsSystem.tickPipelinePhysics} — one call per substep, so the rebuild lands
 * before the next substep. A substep that changes no block costs one small scope object.</p>
 *
 * <p>{@code remap = false}: Sable's own class and names. Bytecode-verified against
 * {@code sable-2.0.5+mc1.21.1}: {@code tickPipelinePhysics} contains exactly one call to the target.
 * <b>Re-verify on any {@code sable_version} bump.</b></p>
 */
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class SubLevelPhysicsStepColliderDeferMixin {

    @WrapOperation(
            method = "tickPipelinePhysics",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/PhysicsPipeline;physicsTick(D)V"
            )
    )
    private void dungeontrain$deferCollidersDuringStep(final PhysicsPipeline pipeline, final double timeStep,
                                                       final Operation<Void> original) {
        ColliderBatch.callForced(() -> original.call(pipeline, timeStep));
    }
}
