package games.brennan.dungeontrain.worldgen.density;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.worldgen.BiomeIdOrder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import terrablender.api.EndBiomeRegistry;
import terrablender.worldgen.IExtendedTheEndBiomeSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The End as a Biomes O' Plenty world has it: vanilla's End biomes plus BoP's
 * ({@code end_wilds}, {@code end_reef}, {@code end_corruption}), laid out by TerraBlender. The live End no
 * longer has them — DT's presets give it WorldWeaver's BetterEnd source with BoP excluded — so a BoP
 * End-band pass samples this instead ({@code EndBandSampler}, {@link EndCoreBiomes}).
 *
 * <p>A private vanilla {@link TheEndBiomeSource}, initialised for TerraBlender, answers each point:
 * TerraBlender's patch picks from its End registry by the same erosion zones vanilla uses. This wrapper
 * exists because the source's own possible-biome list is patched too (WorldWeaver answers it with
 * BetterEnd's), and a generator only decorates with the features of biomes its source says it can
 * produce — so it reports vanilla + TerraBlender's End biomes itself, sorted by id so feature indexes
 * (and every decoration seed) stay the same each boot.</p>
 *
 * <p>Never registered and never saved: it lives inside a sample-only generator, so its codec is a
 * stub that refuses to be used.</p>
 */
public final class BopEndBiomeSource extends BiomeSource {

    private static final MapCodec<BopEndBiomeSource> CODEC = MapCodec.unit(() -> {
        throw new UnsupportedOperationException("BopEndBiomeSource is sample-only and never serialised");
    });

    private final BiomeSource layout;
    private final Set<Holder<Biome>> possible;

    private BopEndBiomeSource(BiomeSource layout, Set<Holder<Biome>> possible) {
        this.layout = layout;
        this.possible = possible;
    }

    /** Build for world seed {@code seed}. Throws when TerraBlender cannot initialise the End layout. */
    public static BopEndBiomeSource create(RegistryAccess registries, long seed) {
        HolderGetter<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
        TheEndBiomeSource source = TheEndBiomeSource.create(biomes);
        ((IExtendedTheEndBiomeSource) source).initializeForTerraBlender(registries, seed);
        List<Holder<Biome>> all = new ArrayList<>();
        for (ResourceKey<Biome> key : List.of(Biomes.THE_END, Biomes.END_HIGHLANDS, Biomes.END_MIDLANDS,
                Biomes.SMALL_END_ISLANDS, Biomes.END_BARRENS)) {
            biomes.get(key).ifPresent(all::add);
        }
        for (List<WeightedEntry.Wrapper<ResourceKey<Biome>>> list : List.of(EndBiomeRegistry.getHighlandsBiomes(),
                EndBiomeRegistry.getMidlandsBiomes(), EndBiomeRegistry.getEdgeBiomes(), EndBiomeRegistry.getIslandBiomes())) {
            for (WeightedEntry.Wrapper<ResourceKey<Biome>> entry : list) biomes.get(entry.data()).ifPresent(all::add);
        }
        return new BopEndBiomeSource(source, BiomeIdOrder.sortedCopy(all));
    }

    /** True when TerraBlender's End registry holds any Biomes O' Plenty biome. */
    public boolean hasBop() {
        return possible.stream().anyMatch(OverworldStretchBiomes::isBop);
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return possible.stream();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        return layout.getNoiseBiome(quartX, quartY, quartZ, sampler);
    }
}
