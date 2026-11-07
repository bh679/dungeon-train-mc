package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.DiskTestMemo;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scopes {@link DiskTestMemo} to one placed disk feature: its many disks share the memo, and it ends before
 * the next feature (any feature: a non-disk start also ends a memo an exception left open).
 */
@Mixin(PlacedFeature.class)
public abstract class PlacedFeatureDiskMemoMixin {

    @Inject(method = "placeWithBiomeCheck", at = @At("HEAD"))
    private void dungeontrain$beginDiskMemo(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin,
                                            CallbackInfoReturnable<Boolean> cir) {
        PlacedFeature self = (PlacedFeature) (Object) this;
        DiskTestMemo.begin(level, self.feature().value().config() instanceof DiskConfiguration disk ? disk.target() : null, origin);
    }

    @Inject(method = "placeWithBiomeCheck", at = @At("RETURN"))
    private void dungeontrain$endDiskMemo(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin,
                                          CallbackInfoReturnable<Boolean> cir) {
        DiskTestMemo.end();
    }
}
