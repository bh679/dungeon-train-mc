package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.ryanhcode.sable.util.LevelAccelerator;
import games.brennan.dungeontrain.ship.sable.NoSyncLoadChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Opts the Rapier physics pipeline's {@link LevelAccelerator} into <b>non-loading</b> chunk
 * access (see {@link LevelAcceleratorNoSyncLoadMixin}).
 *
 * <p>{@code RapierPhysicsPipeline} builds one {@code new LevelAccelerator(level)} in its
 * constructor and keeps it for the pipeline's lifetime. {@code handleBlockChange} hands it to
 * {@code VoxelNeighborhoodState.getState} for each of the six neighbours of a changed block, so
 * a block change on the edge of a sub-level plot chunk whose neighbour chunk isn't loaded fell
 * through the accelerator's {@code Level.getChunk(x, z)} fallback and blocked the server thread
 * on a full chunk load — and, for a plot chunk, Dungeon Train's expensive worldgen. Captured by
 * the stall watchdog on two players' machines (#1450, #1335; 0.902.0 and 0.934.0), 5–130 s per
 * stall, most often from a fluid tick inside {@code LevelChunk.postProcessGeneration}:
 * {@code FlowingFluid.spread → Level.setBlock → SableCommonEvents.handleBlockChange →
 * RapierPhysicsPipeline.handleBlockChange → VoxelNeighborhoodState.getState →
 * LevelAccelerator.grabChunkFast → Level.getChunk → ServerChunkCache.getChunk (managedBlock)}.
 * The player saw it as "frozen again" every time they broke more than one block.</p>
 *
 * <p>With the flag set, an unloaded neighbour reads as air, so the changed block's neighbourhood
 * state (FACE/EDGE/CORNER/INTERIOR) is computed against air on that side. That chunk's blocks
 * aren't in the physics scene while it is unloaded anyway, and Sable's own chunk-load path adds
 * their voxels when it arrives — the same tradeoff {@link SubLevelEntityCollisionNoLoadMixin}
 * already accepts for collision sweeps. Flagging the instance (not a global toggle) keeps every
 * other {@code LevelAccelerator} user in Sable — notably {@code setBlockFast} writes — on the
 * vanilla loading path.</p>
 *
 * <p>String target + {@code remap = false}: {@code RapierPhysicsPipeline} ships in Sable's
 * jar-in-jar ({@code sable_rapier}), as with {@link RapierPipelineFreezeMixin}. Bytecode-verified
 * against {@code sable_rapier-1.21.1-2.0.5}: the constructor has exactly one
 * {@code new LevelAccelerator(Level)} site, stored into the {@code accelerator} field.
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
