package games.brennan.dungeontrain.worldgen.legacy;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBiome;
import games.brennan.dungeontrain.worldgen.legacy.farlands.FarLandsShift;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;

import java.util.EnumMap;
import java.util.Map;

/**
 * The biomes a legacy-band column is shown as. Beta's climate biome at the column (for the Far Lands, at
 * the shifted source column) picks a vanilla
 * overworld biome ({@link BetaBiome#vanillaBiome}) so grass tint, weather, snowfall and mob spawns follow
 * the old biome map; pre-biome Alpha shows as forest, its winter half as snowy plains. Only biomes the overworld source already generates are used — widening its biome
 * set would renumber every chunk's feature steps — and a missing one falls back to the vanilla pick.
 *
 * <p>Published per world from {@code NetherBandContextEvents} (before any chunk bakes) and read on the
 * hot per-quart biome path, so the lookup is a volatile read plus the cached chunk classification.</p>
 */
public final class LegacyBiomes {

    private record Context(BiomeSource overworldSource, long seed, Map<BetaBiome, Holder<Biome>> beta,
                           Holder<Biome> alpha, Holder<Biome> alphaWinter, Holder<Biome> infdev,
                           Holder<Biome> floating, Holder<Biome> classic) {}

    /** Alpha had no biome map: one vanilla biome for the band, a snowy one for its winter half. */
    private static final String ALPHA_BIOME = "forest";
    private static final String ALPHA_WINTER_BIOME = "snowy_plains";

    /** Infdev generated one temperate biome everywhere; plains is its closest overworld match. */
    private static final String INFDEV_BIOME = "plains";

    /** Indev's Normal theme reads as plains too: grass, a few trees and flowers, no climate map. */
    private static final String FLOATING_BIOME = "plains";

    /** Classic had one biome too: its levels read as plains. */
    private static final String CLASSIC_BIOME = "plains";

    private static volatile Context current;

    private LegacyBiomes() {}

    /** Resolve and publish this world's mapping; clears it for a world without a train. */
    public static void publish(ServerLevel overworld) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) {
            current = null;
            return;
        }
        BiomeSource source = overworld.getChunkSource().getGenerator().getBiomeSource();
        Map<BetaBiome, Holder<Biome>> beta = new EnumMap<>(BetaBiome.class);
        for (BetaBiome b : BetaBiome.values()) {
            Holder<Biome> holder = find(source, b.vanillaBiome());
            if (holder != null) beta.put(b, holder);
        }
        current = new Context(source, data.getGenerationSeed(), beta,
                find(source, ALPHA_BIOME), find(source, ALPHA_WINTER_BIOME), find(source, INFDEV_BIOME),
                find(source, FLOATING_BIOME), find(source, CLASSIC_BIOME));
    }

    /** The overworld source's own holder for {@code minecraft:<path>}, or null if it never generates it. */
    private static Holder<Biome> find(BiomeSource source, String path) {
        ResourceLocation id = ResourceLocation.withDefaultNamespace(path);
        for (Holder<Biome> holder : source.possibleBiomes()) {
            if (holder.is(id)) return holder;
        }
        return null;
    }

    public static void clear() {
        current = null;
    }

    /**
     * The forced biome for the quart at block {@code (blockX, blockZ)} of {@code source}, or {@code null}
     * to keep the vanilla pick (not the overworld source, not a legacy chunk, or no mapping).
     */
    public static Holder<Biome> override(Object source, int blockX, int blockZ) {
        Context c = current;
        if (c == null || source != c.overworldSource()) return null;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        LegacyBandKind kind = LegacyBands.kindOfChunk(c.seed(), cycle, blockX >> 4, blockZ >> 4);
        if (kind == null) return null;
        return switch (kind) {
            case BETA -> c.beta().get(LegacyBands.beta(c.seed()).climate().biome(blockX, blockZ));
            case CAVES_OF_CHAOS -> c.beta().get(LegacyBands.chaos(c.seed()).climate().biome(blockX, blockZ));
            case SKYLANDS -> c.beta().get(BetaBiome.SKY);
            case ALPHA -> LegacyBands.isAlphaWinter(cycle, blockX >> 4) ? c.alphaWinter() : c.alpha();
            case INFDEV -> c.infdev();
            case FLOATING -> c.floating();
            case CLASSIC -> c.classic();
            case VOID -> null;                                   // keep the vanilla biome over the void
            case LARGE_BIOMES, AMPLIFIED -> null;                // presets sample the source with their own climate
            case FAR_LANDS -> {
                FarLandsShift shift = FarLandsShift.of(cycle, blockX >> 4, blockZ >> 4);
                yield c.beta().get(LegacyBands.beta(c.seed()).climate()
                        .biome(blockX + shift.dxBlocks(), blockZ + shift.dzBlocks()));
            }
        };
    }
}
