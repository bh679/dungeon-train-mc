package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Closes the second synchronous-chunk-load path in Sable's {@code RapierPhysicsPipeline.handleBlockChange}.
 *
 * <p>{@link RapierPipelineNoSyncLoadMixin} (#1509) made the {@code LevelAccelerator} that
 * {@code VoxelNeighborhoodState.getState} reads through non-loading. The very next statement in the
 * same six-neighbour loop reads the neighbour's state <em>directly</em> —
 * {@code level.getBlockState(neighbourPos)} on the {@link ServerLevel} — to look up its physics
 * data, and on a {@code ServerLevel} that is {@code getChunk(FULL, true)}: a full synchronous
 * generation on the server thread when the neighbour chunk isn't loaded. A player log of
 * 28 Sep 2026 (DT 0.994.0, i.e. with #1509) shows exactly that for 35 s, plus five more 5 s stalls,
 * all from a fluid tick in {@code LevelChunk.postProcessGeneration} setting a block on the edge of a
 * sub-level plot chunk:
 * {@code FlowingFluid.spreadTo → Level.setBlock → SableCommonEvents.handleBlockChange →
 * RapierPhysicsPipeline.handleBlockChange:395 → ServerLevel.getBlockState → ServerChunkCache.getChunk
 * (managedBlock)}.</p>
 *
 * <p>With this wrap, a neighbour in an unloaded chunk reads as air — the same tradeoff #1509 already
 * accepts: that chunk's blocks are not in the physics scene while it is unloaded, and Sable's own
 * chunk-load path adds their voxels when it arrives. Loaded neighbours are read exactly as before.</p>
 *
 * <p>String target + {@code remap = false}: {@code RapierPhysicsPipeline} ships in Sable's
 * jar-in-jar ({@code sable_rapier}). Bytecode-verified against {@code sable_rapier-1.21.1-2.0.5}:
 * {@code handleBlockChange} contains exactly one {@code ServerLevel.getBlockState(BlockPos)} call
 * (offset 103, line 395, inside the {@code Direction.values()} loop); the second
 * {@code getPhysicsDataForBlock} at line 403 takes the changed block's own {@code BlockState}
 * parameter and reads no chunk. <b>Re-verify on any {@code sable_version} bump.</b></p>
 */
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class RapierPipelineBlockChangeNoLoadMixin {

    @WrapOperation(
            method = "handleBlockChange",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
            )
    )
    private BlockState dungeontrain$neighbourWithoutLoading(final ServerLevel level, final BlockPos pos,
                                                             final Operation<BlockState> original) {
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return Blocks.AIR.defaultBlockState();
        }
        return original.call(level, pos);
    }
}
