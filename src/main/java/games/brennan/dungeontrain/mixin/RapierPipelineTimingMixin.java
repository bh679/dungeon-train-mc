package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.ship.sable.PhysicsStepTimer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times Sable's native physics step for the {@code [mspt] physMs=} field (see
 * {@link PhysicsStepTimer}). Pure observation: two {@code HEAD}/{@code RETURN} probes around
 * {@code physicsTick(double)} — called once per substep from
 * {@code SubLevelPhysicsSystem.tickPipelinePhysics} and the one place {@code Rapier3D.step} is
 * reached — and a counter on {@code handleBlockChange}, the per-block voxel-collider update that
 * mining or placing a block on a carriage triggers. Nothing is cancelled or modified.
 *
 * <p>String target + {@code remap = false} for the same reason as {@link RapierPipelineFreezeMixin}:
 * {@code RapierPhysicsPipeline} ships in Sable's jar-in-jar and is not on DT's compile classpath.
 * {@code handleBlockChange}'s parameters include {@code SectionPos}/{@code LevelChunkSection}/
 * {@code BlockState}, all vanilla, so the handler compiles; it takes no arguments here because the
 * probe only counts. <b>Re-audit both targets on any {@code sable_version} bump</b> — both were
 * byte-identical 2.0.2→2.0.5.</p>
 */
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class RapierPipelineTimingMixin {

    /** Start of the in-flight {@code physicsTick}; only ever read on the same thread that wrote it. */
    @Unique
    private long dungeonTrain$stepStartNanos;

    @Inject(method = "physicsTick", at = @At("HEAD"))
    private void dungeonTrain$stepBegin(double dt, CallbackInfo ci) {
        dungeonTrain$stepStartNanos = System.nanoTime();
    }

    @Inject(method = "physicsTick", at = @At("RETURN"))
    private void dungeonTrain$stepEnd(double dt, CallbackInfo ci) {
        PhysicsStepTimer.addStepNanos(System.nanoTime() - dungeonTrain$stepStartNanos);
    }

    @Inject(method = "handleBlockChange", at = @At("HEAD"))
    private void dungeonTrain$countBlockChange(CallbackInfo ci) {
        PhysicsStepTimer.countBlockChange();
    }
}
