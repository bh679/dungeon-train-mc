package games.brennan.dungeontrain.worldgen.legacy;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaTerrain;
import games.brennan.dungeontrain.worldgen.legacy.classic.ClassicLevels;
import net.minecraft.Util;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
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

    /** Drop the built Classic levels (server stop) — they are rebuilt on demand, byte-identically. */
    public static void releaseLevels() {
        ClassicLevels c = classic;
        if (c != null) c.clear();
        classic = null;
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

    private static volatile ClassicLevels classic;

    /** The Classic level tiles + cache for {@code seed}; one shared instance per seed. */
    public static ClassicLevels classic(long seed) {
        ClassicLevels c = classic;
        if (c != null && c.seed() == seed) return c;
        synchronized (LegacyBands.class) {
            if (classic == null || classic.seed() != seed) {
                if (classic != null) classic.clear();
                classic = new ClassicLevels(seed, Util.backgroundExecutor());
            }
            return classic;
        }
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
