package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.train.GrowthContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A tree that grows on a carriage counts as a passenger's addition, like bamboo
 * ({@link BambooStalkGrowthMixin}): every cell it fills is marked while it grows
 * ({@link GrowthContext}), so a branch that sticks out past the roof breaks off when it hits terrain.
 *
 * <p>{@code growTree} is the one seam for every sapling (random tick and bone meal), the mangrove
 * propagule and azalea bone meal.</p>
 */
@Mixin(TreeGrower.class)
public abstract class TreeGrowthMixin {

    @WrapMethod(method = "growTree")
    private boolean dungeontrain$markGrownTree(ServerLevel level, ChunkGenerator generator, BlockPos pos,
                                               BlockState state, RandomSource random, Operation<Boolean> original) {
        // Cheap integer pre-check: an ordinary forest never enters the growth context.
        if (!TrackGenerator.isShipyardChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return original.call(level, generator, pos, state, random);
        }
        return GrowthContext.run(() -> original.call(level, generator, pos, state, random));
    }
}
