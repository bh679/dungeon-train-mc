package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import games.brennan.dungeontrain.worldgen.density.OverworldBiomeSourceMark;
import games.brennan.dungeontrain.mixin.MultiNoiseBiomeSourceAccessor;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import terrablender.api.Region;
import terrablender.api.RegionType;
import terrablender.api.Regions;
import terrablender.api.SurfaceRuleManager;
import terrablender.worldgen.IExtendedBiomeSource;
import terrablender.worldgen.IExtendedNoiseGeneratorSettings;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which generator a chunk dimension samples, and the world it is hosted in.
 *
 * <p>Normally the source dimension's own: a Nether room is a slice of this world's Nether. But an
 * editor or builder world has no Nether and no End, and its overworld is superflat — no noise
 * generator anywhere, so every chunk dimension sampled there came back empty and Test the Carriage
 * could never stand one up. When the world has nothing to sample, the dimension is rebuilt from the
 * vanilla {@link WorldPresets#NORMAL} preset instead, seeded with this world's seed — the same trick
 * {@code PresetTerrain} plays for its preset bands: a second {@link NoiseBasedChunkGenerator} that
 * belongs to no level and loads nothing.</p>
 */
public final class PortalChunkSources {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * A source ready to sample.
     *
     * @param host      the level the throwaway chunks are hosted in — registries, structure manager and
     *                  build height; the source dimension's own when it exists
     * @param generator the generator the ground is poured from
     * @param random    that generator's random state
     * @param minY      the lowest row a player could be on in the source dimension
     * @param maxY      the highest — its logical height, so a Nether sample stops under its roof
     * @param fallback  true when the world had nothing to sample and the vanilla preset stood in
     */
    public record Resolved(ServerLevel host, NoiseBasedChunkGenerator generator, RandomState random,
                           int minY, int maxY, boolean fallback) {}

    /** Stand-in generators, by source, for the seed they were built for. */
    private static final Map<PortalChunkTerrain.Source, Resolved> FALLBACKS = new ConcurrentHashMap<>();
    private static volatile long fallbackSeed = Long.MIN_VALUE;

    private PortalChunkSources() {}

    /**
     * The generator to sample {@code source} from, or {@code null} when not even the vanilla preset
     * can supply one (a registry without it — a heavily modded world).
     */
    public static Resolved resolve(MinecraftServer server, PortalChunkTerrain.Source source,
                                   long seed) {
        ServerLevel level = server.getLevel(source.levelKey());
        if (level != null
                && level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator noise) {
            int minY = level.getMinBuildHeight();
            int maxY = Math.min(level.getMaxBuildHeight() - 1,
                minY + level.dimensionType().logicalHeight() - 1);
            return new Resolved(level, noise, level.getChunkSource().randomState(), minY, maxY, false);
        }
        ServerLevel host = level != null ? level : server.overworld();
        if (host == null) return null;
        if (seed != fallbackSeed) {
            FALLBACKS.clear();
            fallbackSeed = seed;
        }
        return FALLBACKS.computeIfAbsent(source, key -> buildFallback(host, key, seed));
    }

    /** Drop the stand-in generators — the next world has its own seed. */
    public static void clear() {
        FALLBACKS.clear();
        fallbackSeed = Long.MIN_VALUE;
    }

    private static Resolved buildFallback(ServerLevel host, PortalChunkTerrain.Source source,
                                          long seed) {
        try {
            WorldPreset preset = host.registryAccess().registryOrThrow(Registries.WORLD_PRESET)
                .getOrThrow(WorldPresets.NORMAL);
            LevelStem stem = preset.createWorldDimensions().dimensions().get(stemFor(source.levelKey()));
            if (stem == null) return null;
            ChunkGenerator generator = stem.generator();
            if (!(generator instanceof NoiseBasedChunkGenerator presetGenerator)) return null;
            // An overworld stand-in is marked as the overworld's source, so DT's own biome choice
            // applies to it as it does to a live world: BoP only in the BoP stretch, vanilla
            // elsewhere. Unmarked, it is plain vanilla everywhere and a BoP room has no BoP in it.
            //
            // Marked on a copy of its own: the preset hands back the stems the registry holds, so
            // marking the preset's source would mark a registry object for the rest of the session.
            if (source.levelKey().equals(Level.OVERWORLD)
                    && presetGenerator.getBiomeSource() instanceof MultiNoiseBiomeSource shared) {
                MultiNoiseBiomeSource own = MultiNoiseBiomeSource.createFromList(
                    ((MultiNoiseBiomeSourceAccessor) shared).dungeontrain$parameters());
                ((OverworldBiomeSourceMark) own).dungeontrain$markOverworld();
                // The two things TerraBlender does to a live overworld and nothing does to this one.
                // A generator decorates only with the features of the biomes its source says it can
                // produce, so a BoP biome the stretch picker hands back came out with none of BoP's
                // trees or plants on it — a BoP room with not one BoP block in it.
                ((IExtendedBiomeSource) own).appendDeferredBiomesList(terraBlenderBiomes(host));
                presetGenerator = new NoiseBasedChunkGenerator(own,
                    Holder.direct(withModSurfaceRules(presetGenerator.generatorSettings().value())));
            }
            NoiseBasedChunkGenerator noise =
                SampleGenerators.forStandIn(host.getServer(), source, presetGenerator, seed);
            // A vanilla room whose vanilla-only stand-in could not be built: nothing to sample.
            if (noise == null) return null;
            RandomState random = RandomState.create(noise.generatorSettings().value(),
                host.registryAccess().lookupOrThrow(Registries.NOISE), seed);
            Holder<DimensionType> type = stem.type();
            // The source dimension's own heights, not the host's: an editor world is 416 tall, and a
            // Nether sample read to its top would stand the room on the bedrock roof.
            int minY = Math.max(type.value().minY(), host.getMinBuildHeight());
            int maxY = Math.min(host.getMaxBuildHeight() - 1,
                type.value().minY() + type.value().logicalHeight() - 1);
            LOGGER.info("[DungeonTrain] Chunk dimension {} samples the vanilla preset: this world has "
                + "no {} generator to sample (y {}..{})", source, source.levelKey().location(), minY, maxY);
            return new Resolved(host, noise, random, minY, maxY, true);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Chunk dimension {} has nothing to sample: no {} generator in "
                + "this world and the vanilla preset could not stand one in", source,
                source.levelKey().location(), e);
            return null;
        }
    }

    /** Every overworld biome a TerraBlender region adds — BoP's, in this pack. */
    private static List<Holder<Biome>> terraBlenderBiomes(ServerLevel host) {
        Registry<Biome> biomes = host.registryAccess().registryOrThrow(Registries.BIOME);
        List<Holder<Biome>> out = new ArrayList<>();
        for (Region region : Regions.get(RegionType.OVERWORLD)) {
            region.addBiomes(biomes, point -> {
                ResourceKey<Biome> key = point.getSecond();
                if (key == Region.DEFERRED_PLACEHOLDER) return;
                biomes.getHolder(key).ifPresent(out::add);
            });
        }
        return out;
    }

    /**
     * A copy of {@code settings} that answers with TerraBlender's namespaced surface rules — how a BoP
     * biome gets its own ground rather than vanilla's grass-over-dirt. TerraBlender switches them on
     * per settings instance, and only for the ones a live world loads; a copy keeps the switch off
     * the registry's own.
     */
    private static NoiseGeneratorSettings withModSurfaceRules(NoiseGeneratorSettings settings) {
        NoiseGeneratorSettings copy = new NoiseGeneratorSettings(settings.noiseSettings(),
            settings.defaultBlock(), settings.defaultFluid(), settings.noiseRouter(),
            settings.surfaceRule(), settings.spawnTarget(), settings.seaLevel(),
            settings.disableMobGeneration(), settings.aquifersEnabled(), settings.oreVeinsEnabled(),
            settings.useLegacyRandomSource());
        ((IExtendedNoiseGeneratorSettings) (Object) copy)
            .setRuleCategory(SurfaceRuleManager.RuleCategory.OVERWORLD);
        return copy;
    }

    private static ResourceKey<LevelStem> stemFor(ResourceKey<Level> level) {
        if (level.equals(Level.NETHER)) return LevelStem.NETHER;
        if (level.equals(Level.END)) return LevelStem.END;
        return LevelStem.OVERWORLD;
    }
}
