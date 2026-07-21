package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side helper for the <b>chuncks</b> band — the fourth looping phase of the {@link WorldGenCycle},
 * appended after the upside-down band's trailing overworld gap. Across the band's X-range the world is
 * mostly <b>void</b>, sprinkled with occasional real overworld chunks:
 *
 * <ul>
 *   <li>{@link Kind#VOID} — the majority; {@code NoiseBasedChunkGeneratorMixin} hands back an all-air
 *       chunk (the same fast path the End void band uses). The floating track bed still crosses.</li>
 *   <li>{@link Kind#FULL} — a vertically complete overworld chunk (vanilla terrain, untouched).</li>
 *   <li>{@link Kind#SLICE} — a top-down slice: vanilla terrain is generated then everything below a flat
 *       per-chunk cut Y is erased ({@code WorldChuncksEvents}), leaving the natural surface on top and a
 *       flat bottom — a floating island of the chunk's upper terrain.</li>
 * </ul>
 *
 * <p>The per-chunk kind is a pure, seed-stable function of the chunk coordinates (see {@link #hash01}),
 * so it is identical across reloads and on every worldgen worker. Only chunks lying <em>entirely</em>
 * inside the band participate; boundary chunks generate as normal terrain (the band has a hard edge, no
 * fade). Layout/positioning lives in {@link WorldGenCycle}; this class only classifies chunks.</p>
 *
 * <p>Thread-safety: reads only the memoised {@link WorldGenCycle#fromConfig()}, the volatile
 * {@link DungeonTrainCommonConfig}, and per-world {@link DungeonTrainWorldData} — the same access pattern
 * {@link DisintegrationBand#isChunkFullyEroded} already uses from C2ME worldgen workers.</p>
 */
public final class ChuncksBand {

    /** Returned by {@link #startX} when the chuncks band is disabled or the world has no train. */
    public static final long OFF = Long.MAX_VALUE;

    /** Per-chunk classification within the band. */
    public enum Kind { VOID, FULL, SLICE }

    /** Blocks of natural terrain kept above the flat slice cut, at most, below the track bed Y. */
    private static final int SLICE_CUT_MIN_BELOW_BED = 24;
    /** Blocks the flat slice cut may sit above the track bed Y (so some slabs float a little higher). */
    private static final int SLICE_CUT_MAX_ABOVE_BED = 8;

    private ChuncksBand() {}

    /**
     * World-X where the cycle is anchored (shared with the other bands via {@link WorldGenCycle}), or
     * {@link #OFF} when the chuncks band is disabled, the world has no train, or the band has no length.
     * Independent of the disintegration/nether/upside-down enable flags.
     */
    public static long startX(ServerLevel overworld) {
        if (!DungeonTrainCommonConfig.isChuncksEnabled()) return OFF;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return OFF;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.period() <= 0L || cycle.chuncksLen() <= 0L) return OFF;
        return cycle.startX();
    }

    /** True if the column at {@code worldX} lies in the chuncks band. */
    public static boolean isInBand(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return false;
        return WorldGenCycle.fromConfig().isInChuncksBand(worldX);
    }

    /**
     * Classify the chunk at {@code (chunkX, chunkZ)}. Uses the position-driven keep-density
     * ({@link WorldGenCycle#chuncksKeepDensityAt}) so the entry fade zone naturally produces sparse void
     * that thickens toward the core. A density {@code ≥ 1.0} — outside the band + fade, or when the band
     * is off — always returns {@link Kind#FULL} (normal terrain, never void).
     *
     * <p>Ordering is deliberate for the runtime hot path (the fluid veto queries this on every water/lava
     * spread in the overworld): the cheap gate — config flag + memoised cycle + pure offset math — runs
     * <b>before</b> any {@code DungeonTrainWorldData} data-storage lookup or noise hash, so out-of-band
     * spreads (the overwhelming majority) fold to a few comparisons and touch neither. Only genuinely
     * in-band chunks reach the per-world seed lookup, and those are memoised (see {@link #cachedKind}).</p>
     */
    public static Kind kindOf(ServerLevel overworld, int chunkX, int chunkZ) {
        if (!DungeonTrainCommonConfig.isChuncksEnabled()) return Kind.FULL;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.chuncksLen() <= 0L) return Kind.FULL;
        double density = cycle.chuncksKeepDensityAt(chunkX << 4);
        if (density >= 1.0) return Kind.FULL;                        // outside the band + fade → normal terrain
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return Kind.FULL;               // no train → no bands
        return cachedKind(data.getGenerationSeed(), chunkX, chunkZ, density, cycle.chuncksSliceRatio());
    }

    /**
     * Pure per-chunk classification (no Minecraft types) — a chunk keeps real terrain with probability
     * {@code keepDensity}, and a kept chunk is a slice with probability {@code sliceRatio}. Deterministic
     * in {@code (seed, chunkX, chunkZ)} and uniform, so both fractions track their knob linearly. Package
     * -private for unit testing; {@link #kindOf} is the world-facing entry point.
     */
    static Kind classify(long seed, int chunkX, int chunkZ, double keepDensity, double sliceRatio) {
        if (hash01(seed, chunkX, chunkZ, KEEP_SALT) >= keepDensity) return Kind.VOID;
        return hash01(seed, chunkX, chunkZ, SLICE_SALT) < sliceRatio ? Kind.SLICE : Kind.FULL;
    }

    // ---- per-chunk classification cache -------------------------------------
    // In-band kinds are stable within a (seed, config) epoch, so memoise them by chunk key: the fluid
    // veto queries the same boundary chunks every fluid tick, and the fill / decoration / bedrock gen
    // passes each re-derive the kind per chunk. ConcurrentHashMap: worldgen workers + the server thread
    // both hit it. Seed-keyed (a fresh world clears it) and cleared on COMMON config reload via
    // #invalidateCache (wired next to WorldGenCycle#invalidateCache). Only in-band chunks are cached
    // (kindOf's density gate returns FULL before reaching here), so no out-of-band entries accumulate.
    private static final ConcurrentHashMap<Long, Kind> KIND_CACHE = new ConcurrentHashMap<>();
    private static volatile long cacheSeed = Long.MIN_VALUE;
    private static final int MAX_CACHE = 1 << 18;                     // ~262k chunks — crude memory bound

    private static Kind cachedKind(long seed, int chunkX, int chunkZ, double density, double sliceRatio) {
        if (seed != cacheSeed) {                                      // new world (or first use) → drop stale kinds
            KIND_CACHE.clear();
            cacheSeed = seed;
        }
        long key = ChunkPos.asLong(chunkX, chunkZ);
        Kind hit = KIND_CACHE.get(key);
        if (hit != null) return hit;
        Kind k = classify(seed, chunkX, chunkZ, density, sliceRatio);
        if (KIND_CACHE.size() >= MAX_CACHE) KIND_CACHE.clear();       // bound memory; recompute is cheap
        KIND_CACHE.put(key, k);
        return k;
    }

    /**
     * Drop the memoised per-chunk kinds. Called on COMMON {@code ModConfigEvent} (the band's position,
     * density, or slice ratio may have changed), alongside {@link WorldGenCycle#invalidateCache()}.
     */
    public static void invalidateCache() {
        KIND_CACHE.clear();
        cacheSeed = Long.MIN_VALUE;
    }

    /**
     * True if the chunk at {@code (chunkMinX, chunkMinZ)} is a void chunk (in-band, not kept). Takes any
     * block coordinate in the chunk (floored to chunk coords). Routes through {@link #kindOf}, which
     * gates and fast-outs internally — no redundant band/data-storage lookup here.
     */
    public static boolean isVoidChunk(ServerLevel overworld, int chunkMinX, int chunkMinZ) {
        return kindOf(overworld, chunkMinX >> 4, chunkMinZ >> 4) == Kind.VOID;
    }

    /**
     * True if the world position {@code (blockX, blockY, blockZ)} is <b>void space</b> in the chuncks band
     * — either a {@link Kind#VOID} chunk (empty everywhere) or the erased underside of a {@link Kind#SLICE}
     * chunk ({@code blockY < } the slice's flat {@linkplain #sliceCutY cut Y}). Unlike {@link #isVoidChunk}
     * (a whole-chunk test used at gen), this is per-<em>block</em>: the fluid veto uses it so liquid can't
     * pour down out of a slice's flat bottom, not just across a void chunk's boundary. Fast-outs via
     * {@link #kindOf} when out-of-band; only slice chunks pay the cut-Y resolution.
     */
    public static boolean isVoidSpace(ServerLevel overworld, int blockX, int blockY, int blockZ) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        Kind kind = kindOf(overworld, chunkX, chunkZ);
        if (kind == Kind.VOID) return true;
        if (kind != Kind.SLICE) return false;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        int bedY = TrackGeometry.from(data.dims(), data.getTrainY()).bedY();
        return blockY < cutY(data.getGenerationSeed(), chunkX, chunkZ, bedY);
    }

    /**
     * Flat cut-off Y for a {@link Kind#SLICE} chunk: everything strictly below this is erased, leaving the
     * natural surface above it. Sampled per chunk in {@code [bedY − SLICE_CUT_MIN_BELOW_BED,
     * bedY + SLICE_CUT_MAX_ABOVE_BED]} so slabs sit around the train's height (where they are visible).
     */
    public static int sliceCutY(ServerLevel overworld, int chunkX, int chunkZ, int bedY) {
        return cutY(DungeonTrainWorldData.get(overworld).getGenerationSeed(), chunkX, chunkZ, bedY);
    }

    /** Pure flat slice-cut Y in {@code [bedY − SLICE_CUT_MIN_BELOW_BED, bedY + SLICE_CUT_MAX_ABOVE_BED]}. */
    static int cutY(long seed, int chunkX, int chunkZ, int bedY) {
        double r = hash01(seed, chunkX, chunkZ, CUT_SALT);
        int span = SLICE_CUT_MIN_BELOW_BED + SLICE_CUT_MAX_ABOVE_BED;      // inclusive window width
        return bedY + SLICE_CUT_MAX_ABOVE_BED - (int) Math.floor(r * (span + 1));
    }

    // ---- per-chunk uniform hash ---------------------------------------------
    // A splitmix64-style finaliser (same idiom as Disintegration#hash01) giving a uniform [0,1) value per
    // (seed, chunkX, chunkZ, salt). Uniform (unlike the spatially-coherent Disintegration#coherentNoise),
    // so keep/slice fractions track the config knobs linearly, and per-chunk independent, so kept chunks
    // scatter instead of clumping. The three salts decorrelate the keep / slice / cut rolls.
    private static final int KEEP_SALT = 1;
    private static final int SLICE_SALT = 2;
    private static final int CUT_SALT = 3;

    private static double hash01(long seed, int a, int b, int salt) {
        long h = seed * 0x9E3779B97F4A7C15L + salt * 0xD1B54A32D192ED03L;
        h ^= (long) a * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h ^= (long) b * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return (h >>> 11) * 0x1.0p-53;
    }
}
