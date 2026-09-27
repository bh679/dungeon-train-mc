package games.brennan.dungeontrain.worldgen.legacy.preset;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.mixin.NoiseRouterDataAccessor;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.SunkZone;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBands;
import games.brennan.dungeontrain.worldgen.density.NetherBandHooks;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.slf4j.Logger;

/**
 * The two <em>modern-preset</em> legacy bands — Large Biomes and Amplified — plus the <em>sunk
 * overworld</em>: the plain overworld router lowered with Amplified ({@link AmplifiedDrop}), which fills the
 * ordinary chunks of the {@link SunkZone} (the gap leading into Amplified and its fades). Unlike the older eras these
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

    /**
     * A preset's generator, the random state its router was seeded into, and how far its terrain is
     * sunk ({@link AmplifiedDrop#NONE} for Large Biomes).
     */
    public record Preset(NoiseBasedChunkGenerator generator, RandomState randomState, AmplifiedDrop drop) {

        /**
         * True when the preset's surface and carver passes must run on its own generator too. Both are
         * anchored on the generator's floor ({@code above_bottom} bedrock and carver lava levels), so on
         * the overworld's generator a sunk band would get a bedrock sheet and lava lakes at the
         * <em>stock</em> floor, halfway up its valleys.
         */
        public boolean ownsSurfaceAndCarvers() {
            return drop.active();
        }
    }

    /**
     * Vanilla's overworld depth gradient — {@code NoiseRouterData.overworld}'s {@code depth} is this plus
     * the (Y-independent) offset spline. Records compare by value, so the router can be searched for it.
     */
    private static final DensityFunction DEPTH_GRADIENT = DensityFunctions.yClampedGradient(-64, 320, 1.5D, -1.5D);

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile PresetTerrain current;

    private final Preset largeBiomes;
    private final Preset amplified;
    /** The ordinary overworld router sunk with Amplified — fills plain chunks of the {@link SunkZone}. */
    private final Preset sunkOverworld;
    /** The live overworld generator these presets stand in for, and its world's train facts. */
    private final Object overworldGenerator;
    private final long generationSeed;
    private final boolean trainWorld;

    private PresetTerrain(Preset largeBiomes, Preset amplified, Preset sunkOverworld,
                          Object overworldGenerator, long generationSeed, boolean trainWorld) {
        this.largeBiomes = largeBiomes;
        this.amplified = amplified;
        this.sunkOverworld = sunkOverworld;
        this.overworldGenerator = overworldGenerator;
        this.generationSeed = generationSeed;
        this.trainWorld = trainWorld;
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

    /** The sunk-overworld preset, or {@code null} when none is published or nothing is sunk. */
    public static Preset sunkOverworld() {
        PresetTerrain t = current;
        return t == null || !t.sunkOverworld.drop().active() ? null : t.sunkOverworld;
    }

    /** True if {@code generator} is one of the published preset generators (the hooks must not re-enter them). */
    public static boolean isPresetGenerator(Object generator) {
        PresetTerrain t = current;
        return t != null && (generator == t.largeBiomes.generator() || generator == t.amplified.generator()
                || generator == t.sunkOverworld.generator());
    }

    /**
     * The sunk preset owning the column at block {@code (blockX, blockZ)} when {@code self} is the
     * overworld's own generator, else {@code null}. Level-free, for the height queries structure placement
     * makes ({@code getBaseHeight}) with only a height accessor in hand — without it a village in the sunk
     * zone is sited on stock-height terrain and hangs 80 blocks over the real ground.
     */
    public static Preset sunkForColumn(Object self, int blockX, int blockZ) {
        PresetTerrain t = current;
        if (t == null || !t.trainWorld || self != t.overworldGenerator) return null;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        int chunkX = blockX >> 4;
        LegacyBandKind kind = LegacyBands.kindOfChunk(t.generationSeed, cycle, chunkX, blockZ >> 4);
        if (kind == LegacyBandKind.AMPLIFIED) return t.amplified.drop().active() ? t.amplified : null;
        if (kind != null) return null;
        if (!t.sunkOverworld.drop().active()) return null;
        return SunkZone.contains(cycle, SunkZone.chunkColumn(chunkX)) ? t.sunkOverworld : null;
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
        AmplifiedDrop drop = AmplifiedDrop.of(overworld);
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        return new PresetTerrain(
                build(base, densityFunctions, noises, biomes, seed, true, false, AmplifiedDrop.NONE),
                build(base, densityFunctions, noises, biomes, seed, false, true, drop),
                build(base, densityFunctions, noises, biomes, seed, false, false, drop),
                generator, data.getGenerationSeed(), data.startsWithTrain());
    }

    private static Preset build(NoiseGeneratorSettings base, HolderGetter<DensityFunction> densityFunctions,
                                HolderGetter<NormalNoise.NoiseParameters> noises, BiomeSource biomes, long seed,
                                boolean largeBiomes, boolean amplified, AmplifiedDrop drop) {
        NoiseRouter router = sink(
                NoiseRouterDataAccessor.dungeontrain$overworld(densityFunctions, noises, largeBiomes, amplified), drop);
        NoiseSettings noise = base.noiseSettings();
        if (drop.active()) {
            noise = NoiseSettings.create(drop.noiseMinY(noise.minY()), drop.noiseHeight(noise.minY(), noise.height()),
                    noise.noiseSizeHorizontal(), noise.noiseSizeVertical());
        }
        NoiseGeneratorSettings settings = new NoiseGeneratorSettings(noise, base.defaultBlock(),
                base.defaultFluid(), router, base.surfaceRule(), base.spawnTarget(), drop.seaLevel(base.seaLevel()),
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
        return new Preset(new NoiseBasedChunkGenerator(biomes, Holder.direct(settings)), randomState, drop);
    }

    /**
     * Lower {@code router}'s terrain by {@code drop.drop()} blocks: its depth gradient is swapped for the
     * same gradient {@code drop} blocks lower, so {@code depth(y)} reads what it used to at {@code y + drop}.
     * Everything built on depth — sloped cheese, the preliminary surface the aquifers read, the cave-biome
     * depth parameter — moves with it; nothing else in the router is anchored to it. Cheaper than a
     * Y-shifted wrapper, which would fight {@code NoiseChunk}'s cell interpolation.
     */
    static NoiseRouter sink(NoiseRouter router, AmplifiedDrop drop) {
        if (!drop.active()) return router;
        DensityFunction sunk = DensityFunctions.yClampedGradient(-64 - drop.drop(), 320 - drop.drop(), 1.5D, -1.5D);
        int[] hits = {0};
        NoiseRouter out = router.mapAll(f -> {
            if (!DEPTH_GRADIENT.equals(f)) return f;
            hits[0]++;
            return sunk;
        });
        // A datapack that rewrites overworld_amplified/depth would leave the band at its stock height —
        // with the attic lid still placed for a sunk one. Say so rather than fail quietly.
        if (hits[0] == 0) LOGGER.warn("[DungeonTrain] Overworld depth gradient not found; sunk terrain is not sunk");
        return out;
    }
}
