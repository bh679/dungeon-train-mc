package games.brennan.dungeontrain.mixin.wover;

import games.brennan.dungeontrain.worldgen.BiomeIdOrder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.betterx.wover.common.generator.impl.compat.LithostitchedBiomeSourceCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;
import java.util.Set;

/**
 * {@code LithostitchedBiomeSourceCompat.replacePossibleBiomes} writes a WorldWeaver biome source's possible
 * biomes straight into the memoised {@code BiomeSource.possibleBiomes} as a {@code Set.copyOf} set, bypassing
 * {@code collectPossibleBiomes}. Store them in biome-id order instead — same reason as
 * {@link WoverPossibleBiomesOrderMixin}.
 */
@Mixin(value = LithostitchedBiomeSourceCompat.class, remap = false)
public abstract class WoverPossibleBiomesCompatOrderMixin {

    @Redirect(method = "replacePossibleBiomes",
              at = @At(value = "INVOKE", target = "Ljava/util/Set;copyOf(Ljava/util/Collection;)Ljava/util/Set;"))
    private static Set<Holder<Biome>> dungeontrain$sortedCopy(Collection<Holder<Biome>> biomes) {
        return BiomeIdOrder.sortedCopy(biomes);
    }
}
