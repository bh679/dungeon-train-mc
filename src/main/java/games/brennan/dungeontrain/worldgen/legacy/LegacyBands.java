package games.brennan.dungeontrain.worldgen.legacy;

import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.alpha.AlphaTerrain;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaTerrain;
import games.brennan.dungeontrain.worldgen.legacy.sky.SkyTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side entry point for the legacy bands: which chunks an old generator owns, and the per-seed
 * generator instances.
 *
 * <p><b>Chunk ownership.</b> A chunk belongs to a legacy band when a seed-stable per-chunk roll falls
 * under the band's ramp at the chunk's west edge ({@link WorldGenCycle#legacyAt}). The core ramp is 1,
 * so every core chunk is old-generator terrain; across the fades the share of old chunks climbs (or
 * falls) linearly, so modern and old chunks interleave and leave the sheer chunk walls you get when an
 * old world is opened in a new version. Deliberately chunk-granular: the whole fill is per chunk.</p>
 *
 * <p>Thread-safety: reads only the memoised {@link WorldGenCycle#fromConfig()}, the volatile config and
 * per-world {@link DungeonTrainWorldData} (the {@code StacksBand.kindOf} pattern), and memoises kinds in
 * a {@link ConcurrentHashMap} keyed per seed. Generators are immutable and shared across workers.</p>
 */
public final class LegacyBands {

    private LegacyBands() {}

    /**
     * The legacy band that owns chunk {@code (chunkX, chunkZ)} of {@code level}, or {@code null} for a
     * modern chunk (outside every band, in a fade chunk that rolled modern, off-overworld, or in a world
     * without a train). Cheap config + cycle gate first — out-of-band chunks never touch world data.
     */
    public static LegacyBandKind kindOfChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (!level.dimension().equals(Level.OVERWORLD)) return null;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.legacyTotalLen() <= 0L) return null;
        WorldGenCycle.LegacyHit hit = cycle.legacyAt(chunkX << 4);
        if (hit == null) return null;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        if (!data.startsWithTrain()) return null;
        return cachedKind(data.getGenerationSeed(), chunkX, chunkZ, hit);
    }

    /**
     * True if the column at {@code worldX} lies in the core of legacy band {@code kind} (fades excluded,
     * like the other bands' {@code isInBand}). False off-overworld, in a world without a train, or when
     * the band is disabled.
     */
    public static boolean isInBand(ServerLevel overworld, LegacyBandKind kind, int worldX) {
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.legacyLen(kind) <= 0L || !cycle.isInLegacyBand(kind, worldX)) return false;
        return DungeonTrainWorldData.get(overworld).startsWithTrain();
    }

    /**
     * True from the start of {@code kind}'s lead gap through the end of its exit fade — see
     * {@link WorldGenCycle#isInLegacyApproachOrBand}. Gates {@code reached_overworld_again}.
     */
    public static boolean isInApproachOrBand(ServerLevel overworld, LegacyBandKind kind, int worldX) {
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (!cycle.isInLegacyApproachOrBand(kind, worldX)) return false;
        return DungeonTrainWorldData.get(overworld).startsWithTrain();
    }

    /** {@link #isInApproachOrBand} for any legacy band. */
    public static boolean isInAnyApproachOrBand(ServerLevel overworld, int worldX) {
        for (LegacyBandKind kind : LegacyBandKind.values()) {
            if (isInApproachOrBand(overworld, kind, worldX)) return true;
        }
        return false;
    }

    /**
     * Pure form of {@link #kindOfChunk} for callers that already hold the seed and cycle (the biome-source
     * hook, which runs without a level). The caller owns the overworld / train-world gate.
     */
    public static LegacyBandKind kindOfChunk(long seed, WorldGenCycle cycle, int chunkX, int chunkZ) {
        if (cycle == null || cycle.legacyTotalLen() <= 0L) return null;
        WorldGenCycle.LegacyHit hit = cycle.legacyAt(chunkX << 4);
        if (hit == null) return null;
        return cachedKind(seed, chunkX, chunkZ, hit);
    }

    /** Pure per-chunk roll: old generator with probability {@code ramp}. Package-private for tests. */
    static LegacyBandKind classify(long seed, int chunkX, int chunkZ, WorldGenCycle.LegacyHit hit) {
        if (hit.ramp() >= 1.0D) return hit.kind();
        return hash01(seed, chunkX, chunkZ) < hit.ramp() ? hit.kind() : null;
    }

    // ---- per-chunk classification cache -------------------------------------------------
    // Fill, surface, carver and decoration gates plus the per-quart biome hook all re-ask the same chunk,
    // so memoise it. Only in-band chunks reach here. NONE stands in for "modern" (the map has no nulls).
    private static final Object NONE = new Object();
    private static final ConcurrentHashMap<Long, Object> KIND_CACHE = new ConcurrentHashMap<>();
    private static volatile long cacheSeed = Long.MIN_VALUE;
    private static final int MAX_CACHE = 1 << 18;

    private static LegacyBandKind cachedKind(long seed, int chunkX, int chunkZ, WorldGenCycle.LegacyHit hit) {
        if (seed != cacheSeed) {
            KIND_CACHE.clear();
            cacheSeed = seed;
        }
        long key = ChunkPos.asLong(chunkX, chunkZ);
        Object cached = KIND_CACHE.get(key);
        if (cached != null) return cached == NONE ? null : (LegacyBandKind) cached;
        LegacyBandKind kind = classify(seed, chunkX, chunkZ, hit);
        if (KIND_CACHE.size() >= MAX_CACHE) KIND_CACHE.clear();
        KIND_CACHE.put(key, kind == null ? NONE : kind);
        return kind;
    }

    /** Drop memoised kinds — COMMON config reload may have moved the bands. */
    public static void invalidateCache() {
        KIND_CACHE.clear();
        cacheSeed = Long.MIN_VALUE;
    }

    // ---- generators ------------------------------------------------------------------

    private static volatile BetaTerrain beta;

    /** The Beta generator for {@code seed}; one shared, immutable instance per seed. */
    public static BetaTerrain beta(long seed) {
        BetaTerrain b = beta;
        if (b != null && b.seed() == seed) return b;
        synchronized (LegacyBands.class) {
            if (beta == null || beta.seed() != seed) beta = new BetaTerrain(seed);
            return beta;
        }
    }

    private static volatile AlphaTerrain alpha;

    /** The Alpha generator for {@code seed}; one shared, immutable instance per seed. */
    public static AlphaTerrain alpha(long seed) {
        AlphaTerrain a = alpha;
        if (a != null && a.seed() == seed) return a;
        synchronized (LegacyBands.class) {
            if (alpha == null || alpha.seed() != seed) alpha = new AlphaTerrain(seed);
            return alpha;
        }
    }

    /**
     * True if Alpha chunk column {@code chunkX} is in Alpha's winter mode: the last
     * {@link LegacyBandConfig#alphaWinterShare()} of the core, and the exit fade after it. Decided at the
     * chunk's west edge so terrain, biome and snow all agree for the chunk.
     */
    public static boolean isAlphaWinter(WorldGenCycle cycle, int chunkX) {
        return isWinter(cycle.legacyCoreProgress(LegacyBandKind.ALPHA, chunkX << 4), LegacyBandConfig.alphaWinterShare());
    }

    /** Pure winter test on a core progress (see {@link WorldGenCycle#legacyCoreProgress}). Package-private for tests. */
    static boolean isWinter(double progress, double share) {
        if (Double.isNaN(progress) || share <= 0.0D) return false;
        return progress >= 1.0D - share;
    }

    private static volatile SkyTerrain sky;

    /** The Sky (Skylands) generator for {@code seed}; one shared, immutable instance per seed. */
    public static SkyTerrain sky(long seed) {
        SkyTerrain s = sky;
        if (s != null && s.seed() == seed) return s;
        synchronized (LegacyBands.class) {
            if (sky == null || sky.seed() != seed) sky = new SkyTerrain(seed);
            return sky;
        }
    }

    // ---- vertical placement -------------------------------------------------------------

    /**
     * Old-generator Y the Skylands band puts at the track bed. Sky's land spans old y ≈ 16–95, densest at
     * 30–40 (its bottom slide ends at 32); bedding at 52 keeps most island mass below the train with the
     * taller islands rising past it — ridden over, tunnelled through, or passed overhead.
     */
    static final int SKY_BED_OLD_Y = 52;
    /** Lowest old Y Sky's slides let land form at (see {@code SkyTerrainTest}); kept at or above the world floor. */
    static final int SKY_LOWEST_LAND_OLD_Y = 16;

    /**
     * World Y of {@code kind}'s old {@code y = 0} in {@code level}. Beta and Alpha are pinned to sea level
     * ({@link LegacyChunkWriter#Y_OFFSET}); Skylands has no sea, so it follows the train's bed instead,
     * clamped so its lowest land stays above the world floor.
     */
    public static int yOffset(LegacyBandKind kind, ServerLevel level) {
        return switch (kind) {
            case BETA, ALPHA -> LegacyChunkWriter.Y_OFFSET;
            case SKYLANDS -> {
                DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
                int bedY = TrackGeometry.from(data.dims(), data.getTrainY()).bedY();
                yield skyYOffset(bedY, level.getMinBuildHeight());
            }
        };
    }

    /** Pure form of the Skylands {@link #yOffset}. Package-private for tests. */
    static int skyYOffset(int bedY, int minBuildY) {
        return Math.max(bedY - SKY_BED_OLD_Y, minBuildY - SKY_LOWEST_LAND_OLD_Y);
    }

    /**
     * True if liquid must not flow into {@code (x, y, z)}: the block sits in a
     * {@linkplain LegacyBandKind#voidBelow void-below} band chunk with nothing but air beneath it down to
     * the bottom of the old column — the open sky under or beside an island. Lets lakes and streams run
     * across island tops and onto lower islands while stopping them at the edges, instead of cascading to
     * the world floor and sheeting across the band (the spheres band's rule). The scan is at most one old
     * column and exits on the first non-air block.
     */
    public static boolean isVoidSpace(ServerLevel level, BlockGetter blocks, int x, int y, int z) {
        LegacyBandKind kind = kindOfChunk(level, x >> 4, z >> 4);
        if (kind == null || !kind.voidBelow()) return false;
        int bottom = Math.max(level.getMinBuildHeight(), yOffset(kind, level));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int by = y - 1; by >= bottom; by--) {
            if (!blocks.getBlockState(pos.set(x, by, z)).isAir()) return false;
        }
        return true;
    }

    // splitmix64-style finaliser, uniform in [0,1) per (seed, chunkX, chunkZ); same idiom as StacksBand.
    private static final int OWN_SALT = 31;

    private static double hash01(long seed, int a, int b) {
        long h = seed * 0x9E3779B97F4A7C15L + OWN_SALT * 0xD1B54A32D192ED03L;
        h ^= (long) a * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h ^= (long) b * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return (h >>> 11) * 0x1.0p-53;
    }
}
