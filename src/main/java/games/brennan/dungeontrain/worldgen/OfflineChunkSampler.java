package games.brennan.dungeontrain.worldgen;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.EnumSet;

/**
 * World generation into a chunk that belongs to nobody — a dimension's real generator run over a
 * throwaway {@link ProtoChunk} without loading, generating or saving any chunk in any world. Shared by
 * the chunk-dimension portal rooms ({@code PortalChunkTerrain} / {@code PortalChunkFeatures}) and the
 * spheres band's other-dimension spheres ({@link ForeignSphereSampler}).
 *
 * <h2>Why it is built this way</h2>
 * <ul>
 *   <li><b>The region is over the sample, not the live level.</b> Features place via heightmap-relative
 *       modifiers, and the live level's heightmaps are wherever the train is; its writes would also be
 *       unbounded. A {@link WorldGenRegion} over the sample ringed by blank neighbours answers from the
 *       sample and catches spill in chunks that are discarded.</li>
 *   <li><b>A {@code ProtoChunk} nobody generated is at {@link ChunkStatus#EMPTY}</b>, and vanilla refuses
 *       to answer biome questions below {@code BIOMES}; the sample and its neighbours are marked
 *       {@code SURFACE} for the noise fill. Once filled, the sample is raised to {@code FEATURES}:
 *       {@code ProtoChunk.setBlockState} only maintains the heightmaps of the chunk's current status,
 *       and {@code SURFACE} tracks just the {@code *_WG} pair — left there, carvers and features would
 *       read the pre-decoration ground height for the rest of the sample (trees stacking inside trees,
 *       and a jungle tree whose trunk lands in another's logs placing no logs, which
 *       {@code CocoaDecorator} can't survive).</li>
 *   <li><b>Whole-chunk fill, never per-column.</b> {@code fillFromNoise} uses vanilla's interpolated cell
 *       grid (~100 ms a chunk); a column asked for alone runs the whole noise router (~28 ms each).</li>
 *   <li><b>Never join {@code fillFromNoise} from inside {@code Util.backgroundExecutor()}</b> — it
 *       schedules there. Callers sample from their own dedicated thread(s).</li>
 * </ul>
 *
 * <p>Stateless; every method touches only the generator, its random state and the chunks passed in.</p>
 */
public final class OfflineChunkSampler {

    /**
     * The block-entity id {@code WorldGenRegion.setBlock} records for a block-entity block written into a
     * proto chunk: a placeholder with no data, which vanilla promotes to a fresh block entity when the
     * chunk goes live ({@code LevelChunk.promotePendingBlockEntity}). A sample never goes live, so its
     * consumers skip these at copy-out and create the block entity fresh at apply.
     */
    private static final String PLACEHOLDER_BLOCK_ENTITY_ID = "DUMMY";

    /** True for the data-less placeholder vanilla records for a block-entity block in a proto chunk. */
    public static boolean isPlaceholderBlockEntity(CompoundTag nbt) {
        return nbt != null && PLACEHOLDER_BLOCK_ENTITY_ID.equals(nbt.getString("id"));
    }

    /** Set on the sampling thread for the span of {@link #decorate}; read by the decoration mixin. */
    private static final ThreadLocal<Boolean> SAMPLING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** True while this thread is decorating an offline sample (see {@link #decorate}). */
    public static boolean isSampling() {
        return SAMPLING.get();
    }

    /**
     * Run the biome decoration pass over a sample. The one way consumers should decorate: while it runs,
     * {@code ChunkGeneratorDecorationMixin} vetoes DT's own features — the track bed lays a corridor in
     * every DT dimension, and the band features (End islands, Nether transition and structures, stacks)
     * key off the display X — none of which belong inside a sphere, an End-band copy or a dimensional
     * carriage room. Copied out as-is, a sampled corridor lands 20 blocks above the real one in the
     * BetterEnd End band and inside lifted spheres. The display world lays its own track.
     */
    public static void decorate(NoiseBasedChunkGenerator generator, Workspace workspace, ProtoChunk chunk) {
        decorate(generator, workspace, chunk, false);
    }

    /**
     * {@link #decorate(NoiseBasedChunkGenerator, Workspace, ProtoChunk)}, and with {@code vanillaOnly}
     * every non-{@code minecraft:} placed feature is vetoed too — see {@link VanillaOnlySample}.
     */
    public static void decorate(NoiseBasedChunkGenerator generator, Workspace workspace, ProtoChunk chunk,
                                boolean vanillaOnly) {
        decorate(generator, workspace, chunk, vanillaOnly, null);
    }

    /**
     * {@link #decorate(NoiseBasedChunkGenerator, Workspace, ProtoChunk, boolean)} with one more namespace's
     * features let through the vanilla-only veto ({@link VanillaOnlySample#allowsHere}).
     */
    public static void decorate(NoiseBasedChunkGenerator generator, Workspace workspace, ProtoChunk chunk,
                                boolean vanillaOnly, String alsoNamespace) {
        SAMPLING.set(Boolean.TRUE);
        VanillaOnlySample.set(vanillaOnly, alsoNamespace);
        try {
            generator.applyBiomeDecoration(workspace.region(), chunk, workspace.structures());
        } finally {
            SAMPLING.set(Boolean.FALSE);
            VanillaOnlySample.set(false);
        }
    }

    /**
     * The beardifier a sample is generated with: nothing at all.
     *
     * <p>Vanilla's is {@code Beardifier.forStructuresInChunk}, which reads the structure starts around the
     * chunk — and reading those loads chunks. Nothing was ever built into a sample site, so a beardifier
     * that contributes zero everywhere is the honest answer. Written out rather than reusing
     * {@code DensityFunctions.BeardifierMarker.INSTANCE}, which is not accessible from here.</p>
     */
    public static final DensityFunctions.BeardifierOrMarker NO_BEARDS =
        new DensityFunctions.BeardifierOrMarker() {
            @Override
            public double compute(DensityFunction.FunctionContext context) {
                return 0.0;
            }

            @Override
            public double minValue() {
                return 0.0;
            }

            @Override
            public double maxValue() {
                return 0.0;
            }
        };

    /**
     * The region a sample is generated in and the structure manager bound to it — built once per sample
     * and handed to every pass. The manager answers out of the throwaway chunks; the level's own would
     * read structure starts out of the world, one chunk load at a time.
     */
    public record Workspace(WorldGenRegion region, StructureManager structures) {}

    private OfflineChunkSampler() {}

    /**
     * A throwaway chunk at {@code pos} with the generator's biomes, marked {@code SURFACE} so vanilla
     * will answer questions about it. Nothing filled yet.
     */
    public static ProtoChunk blankSample(ServerLevel level, NoiseBasedChunkGenerator generator,
                                         RandomState random, ChunkPos pos) {
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level, biomes, null);
        chunk.fillBiomesFromNoise(generator.getBiomeSource(), random.sampler());
        chunk.setPersistedStatus(ChunkStatus.SURFACE);
        return chunk;
    }

    /**
     * Pour the generator's terrain into {@code chunk} and run the surface rules over it — the ground,
     * before carvers or decoration. Returns the filled chunk, or {@code null} if the generator handed
     * back something other than a {@link ProtoChunk}. Blocks the calling thread for the fill; never
     * call it from {@code Util.backgroundExecutor()}.
     */
    public static ProtoChunk fillGround(ServerLevel level, NoiseBasedChunkGenerator generator,
                                        RandomState random, ProtoChunk chunk, Workspace workspace) {
        ChunkAccess filled =
            generator.fillFromNoise(Blender.empty(), random, workspace.structures(), chunk).join();
        if (!(filled instanceof ProtoChunk ground)) return null;
        Heightmap.primeHeightmaps(ground, EnumSet.of(
            Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG,
            Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
        // Vanilla carves at CARVERS and decorates at FEATURES, where setBlockState keeps the FINAL
        // heightmaps (OCEAN_FLOOR, WORLD_SURFACE, MOTION_BLOCKING*) current. At SURFACE only the *_WG
        // pair is tracked, so heightmap-placed features would keep reading the noise-fill ground.
        // FEATURES is still below INITIALIZE_LIGHT, so no light engine is touched.
        ground.setPersistedStatus(ChunkStatus.FEATURES);
        dressSurface(generator, level, random, ground);
        return ground;
    }

    /**
     * Run the dimension's real surface rules over the filled chunk — the pass that turns a hill of the
     * default block into grass, dirt, sand or snow (or nylium, soul sand, basalt).
     *
     * <p>Straight to {@link net.minecraft.world.level.levelgen.SurfaceSystem} rather than through
     * {@code ChunkGenerator.buildSurface}, which would build its noise chunk with a beardifier read off
     * the level's structure starts. The noise chunk is built here with {@link #NO_BEARDS} instead.</p>
     */
    public static void dressSurface(NoiseBasedChunkGenerator generator, ServerLevel level,
                                    RandomState random, ProtoChunk chunk) {
        NoiseGeneratorSettings settings = generator.generatorSettings().value();
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        NoiseChunk noise = NoiseChunk.forChunk(chunk, random, NO_BEARDS, settings,
            fluidPicker(settings), Blender.empty());
        random.surfaceSystem().buildSurface(random, biomeManager(generator, random, level.getSeed()),
            biomes, settings.useLegacyRandomSource(), new WorldGenerationContext(generator, level),
            chunk, noise, settings.surfaceRule());
    }

    /** Cut the caves into {@code chunk} in place. Throws on failure; callers decide how best-effort to be. */
    public static void carve(NoiseBasedChunkGenerator generator, RandomState random, ProtoChunk chunk,
                             Workspace workspace, long worldSeed) {
        generator.applyCarvers(workspace.region(), worldSeed, random,
            biomeManager(generator, random, worldSeed), workspace.structures(), chunk,
            GenerationStep.Carving.AIR);
    }

    /**
     * The same global fluid picker {@code NoiseBasedChunkGenerator} uses — sea level of the dimension's
     * own fluid, with lava in the deep. Rebuilt because vanilla's is private static.
     */
    public static Aquifer.FluidPicker fluidPicker(NoiseGeneratorSettings settings) {
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(
            -54, net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState());
        int seaLevel = settings.seaLevel();
        Aquifer.FluidStatus sea = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, seaLevel) ? lava : sea;
    }

    /** A biome manager reading the generator's own biome source rather than any level's chunks. */
    public static BiomeManager biomeManager(NoiseBasedChunkGenerator generator, RandomState random,
                                            long worldSeed) {
        return new BiomeManager(
            (x, y, z) -> generator.getBiomeSource().getNoiseBiome(x, y, z, random.sampler()),
            BiomeManager.obfuscateSeed(worldSeed));
    }

    /** A workspace over {@code chunk} and the ring of throwaway neighbours around it. */
    public static Workspace workspaceFor(ServerLevel level, NoiseBasedChunkGenerator generator,
                                         RandomState random, ProtoChunk chunk) {
        WorldGenRegion region = regionAround(level, chunk,
            ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES));
        return new Workspace(region, level.structureManager().forWorldGenRegion(region));
    }

    /**
     * A region centred on {@code chunk}, ringed by blank chunks out to the step's own radius. The
     * neighbours only catch what a feature at the middle chunk's edge writes past it, and are thrown
     * away; sampling their biomes too cost more than generating the middle chunk.
     */
    private static WorldGenRegion regionAround(ServerLevel level, ProtoChunk chunk, ChunkStep step) {
        ChunkPos centre = chunk.getPos();
        int radius = Math.max(1, step.accumulatedDependencies().getRadius());
        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        StaticCache2D<GenerationChunkHolder> cache = StaticCache2D.create(
            centre.x, centre.z, radius, (x, z) -> {
                ChunkPos pos = new ChunkPos(x, z);
                if (pos.equals(centre)) return new SampleHolder(pos, chunk);
                ProtoChunk blank = new ProtoChunk(pos, UpgradeData.EMPTY, level, biomeRegistry, null);
                blank.setPersistedStatus(ChunkStatus.SURFACE);
                return new SampleHolder(pos, blank);
            });
        return new WorldGenRegion(level, cache, step, chunk);
    }

    /**
     * The least a {@link WorldGenRegion} will accept as a chunk holder: one chunk, always present, never
     * scheduled. None of the chunk system's bookkeeping is read on the path a feature takes to a block.
     */
    private static final class SampleHolder extends GenerationChunkHolder {

        private final ChunkAccess chunk;

        private SampleHolder(ChunkPos pos, ChunkAccess chunk) {
            super(pos);
            this.chunk = chunk;
        }

        @Override
        public ChunkAccess getChunkIfPresent(ChunkStatus status) {
            return chunk;
        }

        @Override
        public ChunkAccess getChunkIfPresentUnchecked(ChunkStatus status) {
            return chunk;
        }

        @Override
        public ChunkAccess getLatestChunk() {
            return chunk;
        }

        @Override
        public ChunkStatus getPersistedStatus() {
            return ChunkStatus.FEATURES;
        }

        @Override
        public int getTicketLevel() {
            return 0;
        }

        @Override
        public int getQueueLevel() {
            return 0;
        }
    }
}
