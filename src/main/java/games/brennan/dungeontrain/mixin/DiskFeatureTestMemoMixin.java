package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.worldgen.DiskTestMemo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.DiskFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Routes each disk column's target test through {@link DiskTestMemo}, which skips the repeats. */
@Mixin(DiskFeature.class)
public abstract class DiskFeatureTestMemoMixin {

    @WrapOperation(method = "placeColumn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/blockpredicates/BlockPredicate;test(Ljava/lang/Object;Ljava/lang/Object;)Z"))
    private boolean dungeontrain$rememberedTest(BlockPredicate target, Object level, Object pos, Operation<Boolean> original) {
        return DiskTestMemo.test(target, (WorldGenLevel) level, (BlockPos) pos);
    }
}
