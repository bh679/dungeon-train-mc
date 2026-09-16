package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.ryanhcode.sable.util.LevelAccelerator;
import games.brennan.dungeontrain.ship.sable.NoSyncLoadChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Opts the Rapier physics pipeline's long-lived {@link LevelAccelerator} into <b>non-loading</b>
 * chunk access (see {@link LevelAcceleratorNoSyncLoadMixin}). Issue #1450, root cause of #1335.
 *
 * <p>{@code RapierPhysicsPipeline} builds one {@code new LevelAccelerator(level)} per
 * {@code ServerLevel} in its constructor and uses it purely for <em>reads</em>: {@code getChunk}
 * / {@code getBlockState} / {@code VoxelNeighborhoodState.getState} in {@code handleBlockChange},
 * {@code handleChunkSectionAddition} and {@code processCollisionEffects}, plus {@code clearCache}
 * in {@code tick}. No block write goes through it, so nothing can be dropped into the stand-in
 * empty chunk.</p>
 *
 * <p><b>Why:</b> a fluid spreading on a carriage ({@code LavaFluid.spreadTo → Level.setBlock})
 * reaches {@code SableCommonEvents.handleBlockChange → RapierPhysicsPipeline.handleBlockChange →
 * VoxelNeighborhoodState.getState}, which classifies the changed voxel by reading its 26
 * neighbours through this accelerator. When a neighbour sits in a not-yet-loaded plot chunk the
 * accelerator's fallback {@code Level.getChunk(x, z)} synchronously loaded <em>and generated</em>
 * it on the server thread — DT's expensive plot worldgen — and while parked the server polled more
 * chunk-promotion tasks that nested the same thing. Player logs (2026-09-16) show 5–129 s stalls
 * with exactly this stack, with {@code LevelAcceleratorNoSyncLoadMixin}'s wrap <em>in</em> the
 * path but falling through because only the entity-collision sweep's instance was flagged.</p>
 *
 * <p>Trade-off: a collider face against a not-yet-loaded neighbour classifies as exposed —
 * conservative (an extra collider face, never a missing one) — and is rebuilt when that chunk's own
 * {@code handleChunkSectionAddition} fires.</p>
 *
 * <p>String target + {@code remap = false}: {@code RapierPhysicsPipeline} ships in Sable's
 * jar-in-jar ({@code sable_rapier}) and is not on DT's compile classpath (same as
 * {@link RapierPipelineFreezeMixin}). Bytecode-verified against {@code sable-2.0.5+mc1.21.1}:
 * {@code <init>(ServerLevel)} has exactly one {@code new LevelAccelerator} site.
 * <b>Re-verify on any {@code sable_version} bump.</b></p>
 */
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class RapierPipelineNoSyncLoadMixin {

    @ModifyExpressionValue(
            method = "<init>",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/world/level/Level;)Ldev/ryanhcode/sable/util/LevelAccelerator;"
            )
    )
    private LevelAccelerator dungeontrain$flagPipelineAcceleratorNoSyncLoad(final LevelAccelerator accel) {
        ((NoSyncLoadChunkAccess) (Object) accel).dungeontrain$setNoSyncLoad(true);
        return accel;
    }
}
