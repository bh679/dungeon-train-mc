package games.brennan.dungeontrain.mixin.wover;

import games.brennan.dungeontrain.worldgen.BiomeIdOrder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.betterx.wover.generator.api.biomesource.WoverBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.stream.Stream;

/**
 * WorldWeaver biome sources report their possible biomes in biome-id order.
 *
 * <p>The generator's per-step feature list is sorted out of {@code possibleBiomes()}
 * ({@code FeatureSorter.buildFeaturesPerStep}), and each feature's decoration seed comes from its index in
 * that list. WorldWeaver keeps its possible biomes in a {@code Set.copyOf} set, whose iteration order changes
 * per boot, so the feature indexes — and every BetterEnd tree, spire and island placed from those seeds —
 * came out different each time the server started. See also {@link WoverPossibleBiomesCompatOrderMixin}, the
 * other path WorldWeaver sets the same list through.</p>
 */
@Mixin(value = WoverBiomeSource.class, remap = false)
public abstract class WoverPossibleBiomesOrderMixin {

    @Inject(method = "collectPossibleBiomes", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$inBiomeIdOrder(CallbackInfoReturnable<Stream<Holder<Biome>>> cir) {
        cir.setReturnValue(cir.getReturnValue().sorted(BiomeIdOrder.BY_ID));
    }
}
