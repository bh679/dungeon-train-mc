package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.WwooDecorationPass;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Outside the WWOO stretch, a feature's biome check asks the biome's <b>vanilla</b> feature list, not
 * the live one WWOO rewrote (see {@link WwooDecorationPass}). A pure pass-through whenever no pass is
 * running on this thread or the biome is one WWOO left alone.
 */
@Mixin(BiomeFilter.class)
public abstract class BiomeFilterMixin {

    @Inject(method = "shouldPlace", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$vanillaBiomeCheck(PlacementContext context, RandomSource random, BlockPos pos,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (!WwooDecorationPass.active()) return;
        PlacedFeature feature = context.topFeature().orElse(null);
        if (feature == null) return;
        Boolean allowed = WwooDecorationPass.biomeAllows(feature, context.getLevel().getBiome(pos));
        if (allowed != null) cir.setReturnValue(allowed);
    }
}
