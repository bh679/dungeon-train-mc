package games.brennan.dungeontrain.worldgen.feature;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.MixBand;
import games.brennan.dungeontrain.worldgen.OfflineChunkSampler;
import games.brennan.dungeontrain.worldgen.VanillaOnlySample;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Runs the End band's own features on a chunk vanilla's decoration loop skipped them in.
 *
 * <p>{@code ChunkGenerator#applyBiomeDecoration} gathers the biomes of the chunk and its eight neighbours,
 * keeps only those in the generator's {@code possibleBiomes()}, and places the features of what is left.
 * The overworld's source lists no End biome, and since the void fix (#2469) an End-band column is End-labelled
 * at <em>every</em> height, so a chunk deep inside the band — the void holds either side of the islands and
 * the island core — has no biome left after the filter and places nothing: no island stamp
 * ({@link DisintegrationFeature}), no track bed ({@link TrackBedFeature}). Both ride on
 * {@code #minecraft:is_overworld} ({@code track_bed_overworld.json}); attaching them to End biomes cannot
 * help, since a feature of a biome outside {@code possibleBiomes()} is never indexed for the dimension.</p>
 *
 * <p>So {@code ChunkGeneratorDecorationMixin} notes whether the loop reached each of the two features and, at
 * the end of decoration, calls {@link #run} to place whichever it did not — in the overworld, on a chunk that
 * touches the End band's void columns ({@link #touchesEndVoid}), islands first so the track rests on them.
 * The random is seeded exactly as vanilla seeds a feature at the top-layer step (decoration seed of the chunk,
 * feature index 0/1), so the fallback is as deterministic as the loop it stands in for.</p>
 */
public final class EndBandFeatureFallback {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final ResourceKey<PlacedFeature> ISLANDS = ResourceKey.create(Registries.PLACED_FEATURE,
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "disintegration"));
    static final ResourceKey<PlacedFeature> TRACK = ResourceKey.create(Registries.PLACED_FEATURE,
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "track_bed"));

    /** The step both features are attached at — seeds the fallback's random like the loop would. */
    private static final int STEP = GenerationStep.Decoration.TOP_LAYER_MODIFICATION.ordinal();

    private EndBandFeatureFallback() {}

    /**
     * True when any column of the chunk at {@code chunkMinX} is an End-void column — End-labelled at every
     * height ({@link WorldGenCycle#isEndVoid}) within the End segment's influence — so vanilla's biome filter
     * can have emptied its feature list. Pure; {@code null} cycle → false.
     */
    public static boolean touchesEndVoid(WorldGenCycle cycle, int chunkMinX) {
        if (cycle == null) return false;
        for (int dx = 0; dx < 16; dx++) {
            int x = chunkMinX + dx;
            if (cycle.endSegmentInfluence(x) && cycle.isEndVoid(x)) return true;
        }
        return false;
    }

    /**
     * Place the island stamp and/or the track bed on {@code chunk} when the decoration loop did not reach them
     * ({@code ranIslands} / {@code ranTrack}). Overworld only; never inside an offline or vanilla-only sample.
     * Never throws — a failure logs and leaves the chunk as the loop left it.
     */
    public static void run(WorldGenLevel level, ChunkGenerator generator, ChunkAccess chunk,
                           boolean ranIslands, boolean ranTrack) {
        if (ranIslands && ranTrack) return;
        try {
            if (OfflineChunkSampler.isSampling() || VanillaOnlySample.isActive()) return;
            ServerLevel serverLevel = level.getLevel();
            if (!serverLevel.dimension().equals(Level.OVERWORLD)) return;
            ServerLevel overworld = serverLevel.getServer() == null ? null : serverLevel.getServer().overworld();
            if (overworld == null || DisintegrationBand.startX(overworld) == DisintegrationBand.OFF) return;
            ChunkPos pos = chunk.getPos();
            WorldGenCycle cycle = MixBand.cycleAt(overworld, pos.x, pos.z);
            if (!touchesEndVoid(cycle, pos.getMinBlockX())) return;

            BlockPos origin = new BlockPos(pos.getMinBlockX(), level.getMinBuildHeight(), pos.getMinBlockZ());
            WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
            long decorationSeed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());
            if (!ranIslands) place(level, generator, random, origin, decorationSeed, ISLANDS, 0);
            if (!ranTrack) place(level, generator, random, origin, decorationSeed, TRACK, 1);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] End-band feature fallback failed at {}", chunk.getPos(), t);
        }
    }

    private static void place(WorldGenLevel level, ChunkGenerator generator, WorldgenRandom random,
                              BlockPos origin, long decorationSeed, ResourceKey<PlacedFeature> key, int index) {
        Optional<Holder.Reference<PlacedFeature>> feature =
                level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE).getHolder(key);
        if (feature.isEmpty()) {
            LOGGER.warn("[DungeonTrain] End-band feature fallback: {} is not registered", key.location());
            return;
        }
        random.setFeatureSeed(decorationSeed, index, STEP);
        feature.get().value().place(level, generator, random, origin);
    }
}
