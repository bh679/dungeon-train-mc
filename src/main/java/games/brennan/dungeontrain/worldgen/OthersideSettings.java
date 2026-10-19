package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.slf4j.Logger;

/**
 * Deeper Darker's Otherside as a set of generation rules, without its level: the {@code deeperdarker:otherside}
 * noise settings and biome source read from the world's registries (the same {@code LevelStem} the server
 * creates the dimension from), with a {@link RandomState} seeded like the live dimension's — so the Deeper
 * Dark core generates exactly what the Otherside would at the same block coordinates, but in place in the
 * overworld chunk ({@code feature.NetherCoreStamp}), with no throwaway chunk and nothing read from the
 * Otherside {@code ServerLevel}.
 *
 * <p>Resolved once per server at {@code NetherBandContextEvents.publish} and kept on the
 * {@code NetherBandContext} snapshot; every field is immutable and thread-safe. {@code null} when the
 * settings or the stem are missing (Deeper Darker absent): the Deeper Dark passes then fall back to a
 * vanilla Nether core, as before.</p>
 *
 * @param settings     the Otherside's noise settings
 * @param random       a random state for them, seeded with the world seed (as the live dimension's is)
 * @param generator    a private noise generator over the Otherside's own biome source — its height
 *                     ({@code y 0..128}) is what the surface rule, carver and structure anchors resolve against
 * @param biomes       the Otherside's biome source
 * @param finalDensity the Otherside's terrain density
 * @param cellW        the Otherside's noise-cell width
 * @param cellH        the Otherside's noise-cell height
 * @param seaLevel     the Otherside's sea level (sculk grime below it)
 * @param defaultBlock sculk stone
 * @param defaultFluid sculk grime
 * @param surfaceNoise vanilla's {@code minecraft:surface} noise under this random state — the surface depth
 * @param noiseRandom  the random state's base positional random — the surface depth's jitter
 */
public record OthersideSettings(NoiseGeneratorSettings settings, RandomState random, NoiseBasedChunkGenerator generator,
                                BiomeSource biomes, DensityFunction finalDensity, int cellW, int cellH, int seaLevel,
                                BlockState defaultBlock, BlockState defaultFluid, NormalNoise surfaceNoise,
                                PositionalRandomFactory noiseRandom) {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The Otherside's noise settings. */
    public static final ResourceKey<NoiseGeneratorSettings> NOISE_SETTINGS = ResourceKey.create(
            Registries.NOISE_SETTINGS, ResourceLocation.fromNamespaceAndPath(OthersideCore.NAMESPACE, "otherside"));
    /** The Otherside's level stem. */
    public static final ResourceKey<LevelStem> STEM = ResourceKey.create(
            Registries.LEVEL_STEM, ResourceLocation.fromNamespaceAndPath(OthersideCore.NAMESPACE, "otherside"));

    /**
     * Resolve the Otherside's rules from this server's registries, or {@code null} when it has none. Never
     * throws — a failure leaves the Deeper Dark passes vanilla Nether and is logged once per start.
     */
    public static OthersideSettings resolve(MinecraftServer server) {
        if (server == null) return null;
        try {
            Registry<NoiseGeneratorSettings> noiseSettings = server.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS);
            Holder<NoiseGeneratorSettings> holder = noiseSettings.getHolder(NOISE_SETTINGS).orElse(null);
            LevelStem stem = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(STEM);
            if (holder == null || stem == null) {
                LOGGER.debug("[DungeonTrain] No Otherside settings/stem registered — Deeper Dark passes stay vanilla Nether");
                return null;
            }
            NoiseGeneratorSettings settings = holder.value();
            BiomeSource biomes = stem.generator().getBiomeSource();
            long seed = server.getWorldData().worldGenOptions().seed();
            RandomState random = RandomState.create(settings, server.registryAccess().lookupOrThrow(Registries.NOISE), seed);
            NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(biomes, holder);
            // The SurfaceSystem's own noise random: RandomState builds it from this exact fork of the settings'
            // random source (RandomState's constructor), so the surface depth here matches the Otherside's.
            PositionalRandomFactory noiseRandom = settings.getRandomSource().newInstance(seed).forkPositional();
            return new OthersideSettings(settings, random, generator, biomes, random.router().finalDensity(),
                    settings.noiseSettings().getCellWidth(), settings.noiseSettings().getCellHeight(),
                    settings.seaLevel(), settings.defaultBlock(), settings.defaultFluid(),
                    random.getOrCreateNoise(Noises.SURFACE), noiseRandom);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Failed to resolve the Otherside's generation settings; Deeper Dark passes stay vanilla Nether", t);
            return null;
        }
    }

    /** The Otherside's climate sampler (its biome source is sampled with it). */
    public Climate.Sampler sampler() {
        return random.sampler();
    }

    /** The slab's geometry on a bed at {@code bedY}, clamped to the world's build limits. One per chunk. */
    public OthersideGeometry open(int bedY, int worldMinY, int worldMaxY) {
        return new OthersideGeometry(this, bedY, worldMinY, worldMaxY);
    }

    /**
     * The surface depth vanilla's {@code SurfaceSystem.getSurfaceDepth} would give this column under the
     * Otherside's random state — what {@code add_surface_depth} adds to a {@code stone_depth} check.
     */
    public int surfaceDepth(int worldX, int worldZ) {
        double d0 = surfaceNoise.getValue(worldX, 0.0, worldZ);
        return (int) (d0 * 2.75 + 3.0 + noiseRandom.at(worldX, 0, worldZ).nextDouble() * 0.25);
    }
}
