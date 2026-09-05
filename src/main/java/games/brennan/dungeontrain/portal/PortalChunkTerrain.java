package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a {@link PortalRoomMode#CHUNK_DIMENSION} room's terrain comes from: one chunk of ordinary
 * world generation, sampled out of a real dimension's generator and sliced so its surface lands on
 * the corridor doors.
 *
 * <h2>Sampled, never loaded</h2>
 * <p>Nothing here touches a chunk in the world. A forced {@code getChunk(FULL, true)} on the server
 * tick is the shape of the Sable worldgen deadlock — the reason {@code PortalRoomTiler.chunksLoaded}
 * asks rather than forces — and a portal room is stamped from inside exactly that loop. So the
 * generator is asked for its <b>columns</b> ({@link ChunkGenerator#getBaseColumn}, which builds its
 * own noise chunk and consults no world state), the result is poured into a throwaway
 * {@link ProtoChunk} that belongs to nobody, and the dimension's real surface rules are run over
 * that. No chunk is generated, loaded, saved or kept, in this world or any other.</p>
 *
 * <p>It is the same trick {@code NetherCoreGeometry} already plays on the Nether's density router,
 * one level up: that samples a function, this samples the whole stack of them that turns noise into
 * grass. What is deliberately <b>not</b> sampled is anything needing a {@code WorldGenRegion} —
 * carvers, ores, trees, structures — because a region means chunks, and chunks mean loading.</p>
 *
 * <h2>Off the tick, then cached</h2>
 * <p>Sampling takes tens of milliseconds, so it runs on {@link Util#backgroundExecutor()} and the
 * caller gets {@code null} until it lands ({@link #slice}). A structure is re-stamped every time the
 * train drifts past {@code TWIN_MAX_DRIFT}, so the answer is cached per pair key: a pair's chunk is
 * sampled once and re-stamped from memory for the life of the world, which is also what stops the
 * ground moving under a player who is standing on it.</p>
 */
public final class PortalChunkTerrain {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Edge length of the sampled cube — one chunk, on every axis. */
    public static final int SIZE = 16;

    /**
     * Room-local Y the sampled surface is slid onto: the row a player's feet land on when they walk
     * out of the corridor, which is the room's door line.
     *
     * <p>Nine, and not by taste: {@link PortalRoomLayout#maxDoorHeightOffset} allows a door to sit
     * at most {@code height - minHeight} above a room's floor, which in a 16-tall box with a 7-tall
     * corridor is exactly nine. It is therefore the deepest cross-section of ground this box can put
     * under a player's feet, and the variant's own door-height offset must agree with it — see
     * {@code chunk_dimension} in the portal room {@code weights.json}.</p>
     */
    public static final int SURFACE_ROW = 9;

    /** Blocks of column filled below the anchor before the surface rules run, for their depth tests. */
    private static final int CONTEXT_BELOW = 48;

    /** Blocks filled above it, so an overhang above the slice still shapes what is inside it. */
    private static final int CONTEXT_ABOVE = 24;

    /** Headroom an anchor row needs above it to count as somewhere a player can stand. */
    private static final int ANCHOR_HEADROOM = 5;

    /** How far from the origin, in chunks, sample sites are scattered. */
    private static final int SAMPLE_SPREAD = 60_000;

    /** Crude bound on the cache: a world with more portal pairs than this drops the oldest wholesale. */
    private static final int MAX_CACHE = 512;

    private static final int ROLL_SALT = 11;
    private static final int SITE_X_SALT = 12;
    private static final int SITE_Z_SALT = 13;

    /** Which dimension's generation a chunk dimension is a slice of. */
    public enum Source {
        OVERWORLD(Level.OVERWORLD, PortalRoomSky.CYCLE),
        NETHER(Level.NETHER, PortalRoomSky.NETHER),
        END(Level.END, PortalRoomSky.END);

        private final ResourceKey<Level> levelKey;
        private final PortalRoomSky sky;

        Source(ResourceKey<Level> levelKey, PortalRoomSky sky) {
            this.levelKey = levelKey;
            this.sky = sky;
        }

        public ResourceKey<Level> levelKey() {
            return levelKey;
        }

        /**
         * The sky a room sampled from here stands under — daylight that follows the clock for the
         * Overworld, and the matching tint for the other two.
         *
         * <p>Chosen by the roll rather than by the variant, unlike every other room: the author of a
         * chunk dimension does not know which dimension a given pair will draw, so the one setting
         * that has to agree with it cannot be authored.</p>
         */
        public PortalRoomSky sky() {
            return sky;
        }
    }

    // Sampled cubes by pair key, and the keys currently being sampled on a worker. Both static, both
    // dropped when the server stops (#clear, called from PortalCarriageEvents.onServerStopped) and
    // whenever the world seed changes under them, for the reason every other pair-keyed map here is:
    // the next world's pair 12 is a different room in a different place.
    private static final Map<Integer, PortalChunkSlice> READY = new ConcurrentHashMap<>();
    private static final Set<Integer> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static volatile long cacheSeed = Long.MIN_VALUE;

    /**
     * The beardifier a sample is generated with: nothing at all.
     *
     * <p>Vanilla's is {@code Beardifier.forStructuresInChunk}, which reads the structure starts
     * around the chunk — and reading those loads chunks, which is the one thing this class exists
     * not to do. Nothing was ever built into a sample site, so there is nothing to flatten terrain
     * under, and a beardifier that contributes zero everywhere is the honest answer rather than a
     * shortcut. Written out rather than reusing {@code DensityFunctions.BeardifierMarker.INSTANCE},
     * which is the router's placeholder for this value and not accessible from here.</p>
     */
    private static final DensityFunctions.BeardifierOrMarker NO_BEARDS =
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

    private PortalChunkTerrain() {}

    /**
     * Which dimension the pair {@code pairKey} samples — a pure function of the world seed and the
     * key, so it is the same answer on every re-stamp and needs nothing stored.
     */
    public static Source rollFor(long worldSeed, int pairKey) {
        Source[] all = Source.values();
        int index = (int) (hash01(worldSeed, pairKey, ROLL_SALT) * all.length);
        return all[Math.min(index, all.length - 1)];
    }

    /** The sky a chunk-dimension pair stands under — its rolled dimension's. */
    public static PortalRoomSky skyFor(long worldSeed, int pairKey) {
        return rollFor(worldSeed, pairKey).sky();
    }

    /**
     * This pair's sampled cube, or {@code null} when it is not ready yet — in which case sampling is
     * started on a worker and a later call answers.
     *
     * <p>Never blocks and never generates a chunk. A caller that gets {@code null} stamps the room's
     * own template and asks again next tick; see {@code PortalChunkDimension}.</p>
     */
    public static PortalChunkSlice slice(ServerLevel level, int pairKey) {
        long seed = level.getSeed();
        if (seed != cacheSeed) {
            READY.clear();
            IN_FLIGHT.clear();
            cacheSeed = seed;
        }
        PortalChunkSlice ready = READY.get(pairKey);
        if (ready != null) return ready;
        request(level, pairKey);
        return null;
    }

    /**
     * Start sampling this pair's cube if nothing is holding it yet.
     *
     * <p>Called from {@code planStructure} as well as from the stamp, so the work is usually already
     * done by the time a player has walked far enough down the train to reach the carriage.</p>
     */
    public static void request(ServerLevel level, int pairKey) {
        long seed = level.getSeed();
        MinecraftServer server = level.getServer();
        if (server == null) return;
        if (READY.containsKey(pairKey)) return;
        if (!IN_FLIGHT.add(pairKey)) return;
        Source source = rollFor(seed, pairKey);
        Util.backgroundExecutor().execute(() -> {
            try {
                PortalChunkSlice slice = sample(server, source, seed, pairKey);
                if (slice != null) {
                    if (READY.size() >= MAX_CACHE) READY.clear();
                    READY.put(pairKey, slice);
                }
            } catch (Throwable t) {
                // A failed sample is a room that stamps as its plain template — never a crashed
                // worker, and never a pair that retries the same failure every tick.
                LOGGER.warn("[DungeonTrain] Chunk dimension sample failed for pair {} ({})",
                    pairKey, source, t);
            } finally {
                IN_FLIGHT.remove(pairKey);
            }
        });
    }

    /** Drop every sampled cube — the next world's pair keys mean different rooms. */
    public static void clear() {
        READY.clear();
        IN_FLIGHT.clear();
        cacheSeed = Long.MIN_VALUE;
    }

    // ---- sampling ------------------------------------------------------------

    /**
     * Sample one cube. Runs on a worker thread and touches nothing but the generator, its random
     * state and a {@link ProtoChunk} of its own.
     */
    private static PortalChunkSlice sample(MinecraftServer server, Source source, long worldSeed,
                                           int pairKey) {
        ServerLevel level = server.getLevel(source.levelKey());
        if (level == null) level = server.overworld();
        if (level == null) return null;
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator noiseGenerator)) return null;
        RandomState random = level.getChunkSource().randomState();
        NoiseGeneratorSettings settings = noiseGenerator.generatorSettings().value();
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);

        ChunkPos pos = siteFor(worldSeed, pairKey);
        int minY = level.getMinBuildHeight();
        // Under the Nether's bedrock roof rather than over it: the logical height is where a
        // dimension stops being somewhere a player can be, and an anchor above it would slice the
        // roof rather than a cavern floor.
        int maxY = Math.min(level.getMaxBuildHeight() - 1,
            minY + level.dimensionType().logicalHeight() - 1);

        NoiseColumn centre = noiseGenerator.getBaseColumn(
            pos.getMiddleBlockX(), pos.getMiddleBlockZ(), level, random);
        int anchor = anchorIn(centre, minY, maxY);

        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level, biomes, null);
        chunk.fillBiomesFromNoise(noiseGenerator.getBiomeSource(), random.sampler());

        int fillLo = Math.max(minY, anchor - CONTEXT_BELOW);
        int fillHi = Math.min(maxY, anchor + CONTEXT_ABOVE);
        fillColumns(noiseGenerator, random, level, chunk, pos, fillLo, fillHi);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(
            Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG,
            Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
        dressSurface(noiseGenerator, level, random, settings, biomes, chunk);

        return readSlice(source, chunk, pos, anchor, minY, maxY);
    }

    /** Pour the generator's own columns into the throwaway chunk, over the window that matters. */
    private static void fillColumns(NoiseBasedChunkGenerator generator, RandomState random,
                                    ServerLevel level, ProtoChunk chunk, ChunkPos pos,
                                    int fillLo, int fillHi) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < SIZE; dx++) {
            for (int dz = 0; dz < SIZE; dz++) {
                int worldX = pos.getMinBlockX() + dx;
                int worldZ = pos.getMinBlockZ() + dz;
                NoiseColumn column = generator.getBaseColumn(worldX, worldZ, level, random);
                for (int y = fillLo; y <= fillHi; y++) {
                    BlockState state = column.getBlock(y);
                    // The chunk starts empty, so air is already what is there — and skipping it
                    // keeps the section palettes off every cell of open sky above the ground.
                    if (state.isAir()) continue;
                    chunk.setBlockState(cursor.set(worldX, y, worldZ), state, false);
                }
            }
        }
    }

    /**
     * Run the dimension's real surface rules over the filled chunk — the pass that turns a hill of
     * the default block into grass, dirt, sand or snow.
     *
     * <p>Straight to {@link net.minecraft.world.level.levelgen.SurfaceSystem} rather than through
     * {@code ChunkGenerator.buildSurface}, which would build its noise chunk with a beardifier read
     * off the level's structure starts — and reading those loads chunks. The noise chunk is built
     * here with {@link #NO_BEARDS} instead: no structures to bear down on a sample nothing was ever
     * built into.</p>
     */
    private static void dressSurface(NoiseBasedChunkGenerator generator, ServerLevel level,
                                     RandomState random, NoiseGeneratorSettings settings,
                                     Registry<Biome> biomes, ProtoChunk chunk) {
        BiomeManager biomeManager = new BiomeManager(
            (x, y, z) -> generator.getBiomeSource().getNoiseBiome(x, y, z, random.sampler()),
            BiomeManager.obfuscateSeed(level.getSeed()));
        NoiseChunk noise = NoiseChunk.forChunk(chunk, random, NO_BEARDS, settings,
            fluidPicker(settings), Blender.empty());
        random.surfaceSystem().buildSurface(random, biomeManager, biomes,
            settings.useLegacyRandomSource(), new WorldGenerationContext(generator, level),
            chunk, noise, settings.surfaceRule());
    }

    /**
     * The same global fluid picker {@code NoiseBasedChunkGenerator} uses — sea level of the
     * dimension's own fluid, with lava in the deep. Rebuilt rather than borrowed because vanilla's
     * is private static and the surface rules need one to read a water table off.
     */
    private static Aquifer.FluidPicker fluidPicker(NoiseGeneratorSettings settings) {
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(
            -54, net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState());
        int seaLevel = settings.seaLevel();
        Aquifer.FluidStatus sea = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, seaLevel) ? lava : sea;
    }

    /** Copy the {@link #SIZE} cube around the anchor out of the sampled chunk, room-local. */
    private static PortalChunkSlice readSlice(Source source, ProtoChunk chunk, ChunkPos pos,
                                              int anchor, int minY, int maxY) {
        BlockState[] states = new BlockState[SIZE * SIZE * SIZE];
        BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < SIZE; y++) {
            int worldY = anchor + (y - SURFACE_ROW);
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    BlockState state = air;
                    if (worldY >= minY && worldY <= maxY) {
                        state = chunk.getBlockState(
                            cursor.set(pos.getMinBlockX() + x, worldY, pos.getMinBlockZ() + z));
                    }
                    states[(y * SIZE + z) * SIZE + x] = state;
                }
            }
        }
        return new PortalChunkSlice(source, SIZE, states);
    }

    /**
     * The row a player's feet go on: the lowest air row with solid ground beneath it and
     * {@link #ANCHOR_HEADROOM} of clearance above, searched down from the top.
     *
     * <p>Not simply {@code getBaseHeight}, which is the open sky above the terrain — right in the
     * Overworld and wrong in the Nether, where it lands on the bedrock roof and would slice the ceiling
     * instead of a cavern floor. Searching for a standable row gives the same answer as the heightmap
     * outdoors and the first real floor under a lid.</p>
     */
    private static int anchorIn(NoiseColumn column, int minY, int maxY) {
        for (int y = maxY - ANCHOR_HEADROOM; y > minY; y--) {
            if (column.getBlock(y - 1).isAir()) continue;
            boolean clear = true;
            for (int h = 0; h < ANCHOR_HEADROOM; h++) {
                if (!column.getBlock(y + h).isAir()) {
                    clear = false;
                    break;
                }
            }
            if (clear) return y;
        }
        // A column with no standable row at all — solid to the top, or empty to the bottom. Half way
        // up is as good an answer as any, and the slice is still a legible cross-section of it.
        return minY + (maxY - minY) / 2;
    }

    /** Where a pair samples from — scattered far from the train, and stable in the seed and key. */
    private static ChunkPos siteFor(long worldSeed, int pairKey) {
        int chunkX = (int) ((hash01(worldSeed, pairKey, SITE_X_SALT) - 0.5) * 2 * SAMPLE_SPREAD);
        int chunkZ = (int) ((hash01(worldSeed, pairKey, SITE_Z_SALT) - 0.5) * 2 * SAMPLE_SPREAD);
        return new ChunkPos(chunkX, chunkZ);
    }

    /**
     * A uniform {@code [0,1)} value per {@code (seed, key, salt)} — the splitmix64 finaliser
     * {@code ChuncksBand.hash01} uses, and for the same reason: the roll has to be pure, cheap and
     * identical on every machine that asks.
     */
    private static double hash01(long seed, int key, int salt) {
        long h = seed * 0x9E3779B97F4A7C15L + salt * 0xD1B54A32D192ED03L;
        h ^= (long) key * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return (h >>> 11) * 0x1.0p-53;
    }
}
