package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.train.GrowthContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FungusBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/** A huge fungus grown on a carriage breaks off like a tree ({@link TreeGrowthMixin}). Bone meal is its only growth. */
@Mixin(FungusBlock.class)
public abstract class FungusGrowthMixin {

    @WrapMethod(method = "performBonemeal")
    private void dungeontrain$markGrownFungus(ServerLevel level, RandomSource random, BlockPos pos,
                                              BlockState state, Operation<Void> original) {
        if (!TrackGenerator.isShipyardChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            original.call(level, random, pos, state);
            return;
        }
        GrowthContext.run(() -> {
            original.call(level, random, pos, state);
            return true;
        });
    }
}
