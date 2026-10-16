package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.train.PlayerPlacedTrainBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bamboo that grows on a carriage counts as a passenger's addition, not part of the train: each new
 * stalk cell is marked in {@link PlayerPlacedTrainBlocks}, so when bamboo grows out past the
 * carriage and hits terrain it breaks off ({@code PlayerBlockBreakOff}) instead of ploughing the
 * world the way a train-built block does.
 *
 * <p>{@code growBamboo} is the one seam for both random-tick growth and bone meal
 * ({@code performBonemeal} calls it at the top stalk); it always writes the new stalk at
 * {@code pos.above()}. The shoot's first growth is {@link BambooSaplingGrowthMixin}.</p>
 */
@Mixin(BambooStalkBlock.class)
public abstract class BambooStalkGrowthMixin {

    @Inject(method = "growBamboo", at = @At("TAIL"))
    private void dungeontrain$markGrownStalk(
            BlockState state, Level level, BlockPos pos, RandomSource random, int age, CallbackInfo ci) {
        if (!(level instanceof ServerLevel server)) return;
        // Cheap integer pre-check: an ordinary bamboo forest never reaches the plot lookup.
        if (!TrackGenerator.isShipyardChunk(pos.getX() >> 4, pos.getZ() >> 4)) return;
        BlockPos grown = pos.above();
        if (!server.getBlockState(grown).is(Blocks.BAMBOO)) return;
        PlayerPlacedTrainBlocks.markIfCarriage(server, grown);
    }
}
