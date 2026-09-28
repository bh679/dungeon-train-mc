package games.brennan.dungeontrain.mixin.wover;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import org.betterx.wover.biome.api.data.BiomeData;
import org.betterx.wover.generator.api.biomesource.WoverBiomePicker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Makes WorldWeaver's biome layout the same on every boot for a given seed.
 *
 * <p>{@code WoverBiomePicker.rebuild} builds its weighted pick list by iterating a {@code HashSet} of
 * biomes, and {@code consumeSubBiomesForSource} walks a registry's {@code entrySet()}. Both are hashed on
 * a {@link ResourceKey}, whose hash is identity-based, so their order — and with it which biome a
 * given roll lands on — changes from boot to boot. The BetterEnd End then lays out differently every time
 * the server starts: chunks generated in one session don't line up with chunks generated in the next, and
 * the End band copies a different End each run. Feeding both loops in biome-id order fixes it; the same
 * picker drives WorldWeaver's Nether (BetterNether), which is made stable too.</p>
 */
@Mixin(value = WoverBiomePicker.class, remap = false)
public abstract class WoverBiomePickerOrderMixin {

    private static final Comparator<WoverBiomePicker.PickableBiome> BY_BIOME_ID =
        Comparator.comparing(b -> b.biomeData.biomeKey.location().toString());

    @Redirect(method = "rebuild",
              at = @At(value = "INVOKE", target = "Ljava/util/Set;forEach(Ljava/util/function/Consumer;)V"))
    private void dungeontrain$addInBiomeIdOrder(Set<WoverBiomePicker.PickableBiome> biomes,
                                                Consumer<WoverBiomePicker.PickableBiome> add) {
        biomes.stream().sorted(BY_BIOME_ID).forEach(add);
    }

    @Redirect(method = "consumeSubBiomesForSource",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Registry;entrySet()Ljava/util/Set;"))
    private static Set<Map.Entry<ResourceKey<BiomeData>, BiomeData>> dungeontrain$subBiomesInIdOrder(
            Registry<BiomeData> registry) {
        Set<Map.Entry<ResourceKey<BiomeData>, BiomeData>> sorted = new LinkedHashSet<>();
        registry.entrySet().stream()
            .sorted(Comparator.comparing(e -> e.getKey().location().toString()))
            .forEach(sorted::add);
        return sorted;
    }
}
