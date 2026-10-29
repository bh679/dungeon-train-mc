package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.train.GrowthContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/** A huge mushroom grown on a carriage breaks off like a tree ({@link TreeGrowthMixin}). */
@Mixin(MushroomBlock.class)
public abstract class MushroomGrowthMixin {

    @WrapMethod(method = "growMushroom")
    private boolean dungeontrain$markGrownMushroom(ServerLevel level, BlockPos pos, BlockState state,
                                                   RandomSource random, Operation<Boolean> original) {
        if (!TrackGenerator.isShipyardChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return original.call(level, pos, state, random);
        }
        return GrowthContext.run(() -> original.call(level, pos, state, random));
    }
}
