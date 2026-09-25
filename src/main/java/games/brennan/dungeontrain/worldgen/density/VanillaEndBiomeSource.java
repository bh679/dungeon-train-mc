package games.brennan.dungeontrain.worldgen.density;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.stream.Stream;

/**
 * {@link VanillaEndBiomes} as a {@link BiomeSource}, so a chunk generator can be built that places
 * vanilla's End biomes — for an offline sample of the End as it looks without BetterEnd, which patches
 * {@code TheEndBiomeSource} itself.
 *
 * <p>Never registered and never saved: it lives inside a sample-only generator, so its codec is a
 * stub that refuses to be used.</p>
 */
public final class VanillaEndBiomeSource extends BiomeSource {

    private static final MapCodec<VanillaEndBiomeSource> CODEC = MapCodec.unit(() -> {
        throw new UnsupportedOperationException("VanillaEndBiomeSource is sample-only and never serialised");
    });

    private final VanillaEndBiomes biomes;

    public VanillaEndBiomeSource(VanillaEndBiomes biomes) {
        this.biomes = biomes;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return biomes.all().stream();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        return biomes.biomeAtQuart(quartX, quartY, quartZ);
    }
}
