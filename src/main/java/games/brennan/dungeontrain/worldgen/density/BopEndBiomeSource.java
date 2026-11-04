package games.brennan.dungeontrain.worldgen.density;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.worldgen.BiomeIdOrder;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import terrablender.api.EndBiomeRegistry;
import terrablender.config.TerraBlenderConfig;
import terrablender.core.TerraBlender;
import terrablender.worldgen.noise.Area;
import terrablender.worldgen.noise.LayeredNoiseUtil;

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
 * <p>The layout is TerraBlender's own End rule, re-done here <b>without</b> a {@code TheEndBiomeSource}:
 * vanilla's erosion zones (main island, then highlands / midlands / small islands / barrens by the
 * sampler's erosion — the same thresholds as {@link VanillaEndBiomes}) pick one of four TerraBlender
 * biome areas ({@link LayeredNoiseUtil#biomeArea}: its End registry's weighted lists at its configured
 * biome sizes, seeded with the world seed). A {@code TheEndBiomeSource} cannot be delegated to:
 * WorldWeaver patches every instance's {@code getNoiseBiome} at HEAD and answers with BetterEnd biomes
 * before TerraBlender's patch runs, so a "BoP" source built that way showed one BetterEnd biome.</p>
 *
 * <p>Its possible-biome list is vanilla + TerraBlender's End biomes, sorted by id so feature indexes
 * (and every decoration seed) stay the same each boot.</p>
 *
 * <p>Never registered and never saved: it lives inside a sample-only generator, so its codec is a
 * stub that refuses to be used.</p>
 */
public final class BopEndBiomeSource extends BiomeSource {

    private static final MapCodec<BopEndBiomeSource> CODEC = MapCodec.unit(() -> {
        throw new UnsupportedOperationException("BopEndBiomeSource is sample-only and never serialised");
    });

    /** Vanilla's main-island radius, in sections squared (TerraBlender keeps it). */
    private static final long MAIN_ISLAND_SECTIONS_SQ = 4096L;

    private final Registry<Biome> registry;
    private final Holder<Biome> end;
    private final Area highlands;
    private final Area midlands;
    private final Area edge;
    private final Area islands;
    private final Set<Holder<Biome>> possible;

    private BopEndBiomeSource(Registry<Biome> registry, Holder<Biome> end, Area highlands, Area midlands,
                              Area edge, Area islands, Set<Holder<Biome>> possible) {
        this.registry = registry;
        this.end = end;
        this.highlands = highlands;
        this.midlands = midlands;
        this.edge = edge;
        this.islands = islands;
        this.possible = possible;
    }

    /** Build for world seed {@code seed}. Throws when TerraBlender cannot build the End layout. */
    public static BopEndBiomeSource create(RegistryAccess registries, long seed) {
        Registry<Biome> registry = registries.registryOrThrow(Registries.BIOME);
        TerraBlenderConfig config = TerraBlender.CONFIG;
        if (config == null) throw new IllegalStateException("TerraBlender config not loaded");
        Area highlands = LayeredNoiseUtil.biomeArea(registries, seed, config.endHighlandsBiomeSize, EndBiomeRegistry.getHighlandsBiomes());
        Area midlands = LayeredNoiseUtil.biomeArea(registries, seed, config.endMidlandsBiomeSize, EndBiomeRegistry.getMidlandsBiomes());
        Area edge = LayeredNoiseUtil.biomeArea(registries, seed, config.endEdgeBiomeSize, EndBiomeRegistry.getEdgeBiomes());
        Area islands = LayeredNoiseUtil.biomeArea(registries, seed, config.endIslandBiomeSize, EndBiomeRegistry.getIslandBiomes());
        List<Holder<Biome>> all = new ArrayList<>();
        for (ResourceKey<Biome> key : List.of(Biomes.THE_END, Biomes.END_HIGHLANDS, Biomes.END_MIDLANDS,
                Biomes.SMALL_END_ISLANDS, Biomes.END_BARRENS)) {
            registry.getHolder(key).ifPresent(all::add);
        }
        for (List<WeightedEntry.Wrapper<ResourceKey<Biome>>> list : List.of(EndBiomeRegistry.getHighlandsBiomes(),
                EndBiomeRegistry.getMidlandsBiomes(), EndBiomeRegistry.getEdgeBiomes(), EndBiomeRegistry.getIslandBiomes())) {
            for (WeightedEntry.Wrapper<ResourceKey<Biome>> entry : list) registry.getHolder(entry.data()).ifPresent(all::add);
        }
        return new BopEndBiomeSource(registry, registry.getHolderOrThrow(Biomes.THE_END),
                highlands, midlands, edge, islands, BiomeIdOrder.sortedCopy(all));
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
        int blockX = QuartPos.toBlock(quartX);
        int blockY = QuartPos.toBlock(quartY);
        int blockZ = QuartPos.toBlock(quartZ);
        long sx = SectionPos.blockToSectionCoord(blockX);
        long sz = SectionPos.blockToSectionCoord(blockZ);
        if (sx * sx + sz * sz <= MAIN_ISLAND_SECTIONS_SQ) return end;
        double erosion = sampler.erosion().compute(new DensityFunction.SinglePointContext(blockX, blockY, blockZ));
        Area area = switch (VanillaEndBiomes.classify(erosion)) {
            case 1 -> highlands;
            case 2 -> midlands;
            case 3 -> islands;
            default -> edge;
        };
        return registry.getHolder(area.get(quartX, quartZ)).<Holder<Biome>>map(h -> h).orElse(end);
    }
}
