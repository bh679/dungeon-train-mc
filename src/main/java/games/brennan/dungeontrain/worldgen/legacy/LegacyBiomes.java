package games.brennan.dungeontrain.worldgen.legacy;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBiome;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;

import java.util.EnumMap;
import java.util.Map;

/**
 * The biomes a legacy-band column is shown as. Beta's climate biome at the column picks a vanilla
 * overworld biome ({@link BetaBiome#vanillaBiome}) so grass tint, weather, snowfall and mob spawns follow
 * the old biome map. Only biomes the overworld source already generates are used — widening its biome
 * set would renumber every chunk's feature steps — and a missing one falls back to the vanilla pick.
 *
 * <p>Published per world from {@code NetherBandContextEvents} (before any chunk bakes) and read on the
 * hot per-quart biome path, so the lookup is a volatile read plus the cached chunk classification.</p>
 */
public final class LegacyBiomes {

    private record Context(BiomeSource overworldSource, long seed, Map<BetaBiome, Holder<Biome>> beta) {}

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
            ResourceLocation id = ResourceLocation.withDefaultNamespace(b.vanillaBiome());
            for (Holder<Biome> holder : source.possibleBiomes()) {
                if (holder.is(id)) {
                    beta.put(b, holder);
                    break;
                }
            }
        }
        current = new Context(source, data.getGenerationSeed(), beta);
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
        LegacyBandKind kind = LegacyBands.kindOfChunk(c.seed(), WorldGenCycle.fromConfig(), blockX >> 4, blockZ >> 4);
        if (kind == null) return null;
        return switch (kind) {
            case BETA -> c.beta().get(LegacyBands.beta(c.seed()).climate().biome(blockX, blockZ));
        };
    }
}
