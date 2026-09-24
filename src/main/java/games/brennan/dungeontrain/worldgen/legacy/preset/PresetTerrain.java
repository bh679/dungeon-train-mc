package games.brennan.dungeontrain.worldgen.legacy.preset;

import games.brennan.dungeontrain.mixin.NoiseRouterDataAccessor;
import games.brennan.dungeontrain.worldgen.density.NetherBandHooks;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * The two <em>modern-preset</em> legacy bands — Large Biomes and Amplified. Unlike the older eras these
 * are not ports: the terrain is vanilla's own overworld noise router with the preset flag flipped, so
 * each band is a second {@link NoiseBasedChunkGenerator} built from the live overworld's settings
 * (Dungeon Train's {@code overworld*} noise settings are vanilla's router verbatim; only the noise
 * height window and a surface-rule depth differ, and both are kept) with its router rebuilt through
 * {@link NoiseRouterDataAccessor}. The generator shares the overworld's biome source, so the Large
 * Biomes band gets its wide biomes simply by sampling that source with the large climate sampler.
 *
 * <p>One instance per world, published from {@code NetherBandContextEvents} before any chunk bakes and
 * read on the worldgen hot path ({@code NoiseBasedChunkGeneratorMixin}). Immutable after construction.</p>
 */
public final class PresetTerrain {

    /** A preset's generator and the random state its router was seeded into. */
    public record Preset(NoiseBasedChunkGenerator generator, RandomState randomState) {}

    private static volatile PresetTerrain current;

    private final Preset largeBiomes;
    private final Preset amplified;

    private PresetTerrain(Preset largeBiomes, Preset amplified) {
        this.largeBiomes = largeBiomes;
        this.amplified = amplified;
    }

    /** The published presets, or {@code null} outside a train world / before publish. */
    public static PresetTerrain current() {
        return current;
    }

    /** Build and publish this overworld's presets. */
    public static void publish(ServerLevel overworld) {
        current = resolve(overworld);
    }

    public static void clear() {
        current = null;
    }

    /** The preset for {@code kind}, or {@code null} if {@code kind} is not a preset band or none is published. */
    public static Preset of(LegacyBandKind kind) {
        PresetTerrain t = current;
        if (t == null) return null;
        return switch (kind) {
            case LARGE_BIOMES -> t.largeBiomes;
            case AMPLIFIED -> t.amplified;
            default -> null;
        };
    }

    /** True if {@code generator} is one of the published preset generators (the hooks must not re-enter them). */
    public static boolean isPresetGenerator(Object generator) {
        PresetTerrain t = current;
        return t != null && (generator == t.largeBiomes.generator() || generator == t.amplified.generator());
    }

    /**
     * Build both presets from {@code overworld}'s live generator, or {@code null} if it is not a
     * noise-based generator (nothing to rebuild from).
     */
    static PresetTerrain resolve(ServerLevel overworld) {
        ChunkGenerator generator = overworld.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) return null;
        NoiseGeneratorSettings base = noise.generatorSettings().value();
        HolderGetter<DensityFunction> densityFunctions = overworld.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION);
        HolderGetter<NormalNoise.NoiseParameters> noises = overworld.registryAccess().lookupOrThrow(Registries.NOISE);
        BiomeSource biomes = generator.getBiomeSource();
        long seed = overworld.getSeed();
        return new PresetTerrain(
                build(base, densityFunctions, noises, biomes, seed, true, false),
                build(base, densityFunctions, noises, biomes, seed, false, true));
    }

    private static Preset build(NoiseGeneratorSettings base, HolderGetter<DensityFunction> densityFunctions,
                                HolderGetter<NormalNoise.NoiseParameters> noises, BiomeSource biomes, long seed,
                                boolean largeBiomes, boolean amplified) {
        NoiseRouter router = NoiseRouterDataAccessor.dungeontrain$overworld(densityFunctions, noises, largeBiomes, amplified);
        NoiseGeneratorSettings settings = new NoiseGeneratorSettings(base.noiseSettings(), base.defaultBlock(),
                base.defaultFluid(), router, base.surfaceRule(), base.spawnTarget(), base.seaLevel(),
                base.disableMobGeneration(), base.isAquifersEnabled(), base.oreVeinsEnabled(), base.useLegacyRandomSource());
        // Seed it the way ChunkMap seeds the overworld's own state, so RandomStateMixin's router wrap
        // (and anything else keyed on "this is the overworld") treats the preset router the same way.
        NetherBandHooks.CONSTRUCTING_OVERWORLD.set(Boolean.TRUE);
        RandomState randomState;
        try {
            randomState = RandomState.create(settings, noises, seed);
        } finally {
            NetherBandHooks.CONSTRUCTING_OVERWORLD.set(Boolean.FALSE);
        }
        return new Preset(new NoiseBasedChunkGenerator(biomes, Holder.direct(settings)), randomState);
    }
}
