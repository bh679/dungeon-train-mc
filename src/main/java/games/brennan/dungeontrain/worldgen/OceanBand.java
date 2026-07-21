package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side helper for the <b>ocean</b> band — a looping phase of the {@link WorldGenCycle} inserted in
 * the overworld stretch after the upside-down band's trailing gap and before the chuncks band. Across the
 * band's X-range the world is an <b>open sea of only ocean + island biomes</b> whose water surface is
 * raised to the train track bed height ({@link #waterSurfaceY}, not vanilla sea level 63):
 *
 * <ul>
 *   <li>{@link Kind#OCEAN} — the majority; {@code WorldOceanEvents} floods the natural ocean-biome column
 *       with water up to just below the bed, so the train skims the surface.</li>
 *   <li>{@link Kind#ISLAND} — a sparse minority; {@code WorldOceanEvents} stamps a small sand/dirt/grass
 *       island poking a few blocks above the raised sea.</li>
 * </ul>
 *
 * <p>The per-chunk kind is a pure, seed-stable function of the chunk coordinates (see {@link #hash01}), so
 * it is identical across reloads and on every worldgen worker. Layout/positioning lives in
 * {@link WorldGenCycle}; this class only classifies chunks and answers the fluid-containment query.</p>
 *
 * <p>Thread-safety mirrors {@link ChuncksBand}: reads only the memoised {@link WorldGenCycle#fromConfig()},
 * the volatile {@link DungeonTrainCommonConfig}, and per-world {@link DungeonTrainWorldData}.</p>
 */
public final class OceanBand {

    /** Returned by {@link #startX} when the ocean band is disabled or the world has no train. */
    public static final long OFF = Long.MAX_VALUE;

    /** Per-chunk classification within the band. */
    public enum Kind { OCEAN, ISLAND }

    private OceanBand() {}

    /**
     * World-X where the cycle is anchored (shared with the other bands via {@link WorldGenCycle}), or
     * {@link #OFF} when the ocean band is disabled, the world has no train, or the band has no length.
     * Independent of the disintegration/nether/upside-down enable flags.
     */
    public static long startX(ServerLevel overworld) {
        if (!DungeonTrainCommonConfig.isOceanEnabled()) return OFF;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return OFF;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.period() <= 0L || cycle.oceanLen() <= 0L) return OFF;
        return cycle.startX();
    }

    /** True if the column at {@code worldX} lies in the ocean band. */
    public static boolean isInBand(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return false;
        return WorldGenCycle.fromConfig().isInOceanBand(worldX);
    }

    /**
     * Raised water-surface Y for the band — the train track bed height ({@link TrackGeometry#bedY()}).
     * Clamped to the bed on purpose (never above it), so the corridor interior — which starts at
     * {@code bedY+1} — stays dry by construction whatever the train Y is. Water fills up to
     * {@code waterSurfaceY - 1} (the waterline sits at bed level).
     */
    public static int waterSurfaceY(ServerLevel overworld) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        return TrackGeometry.from(data.dims(), data.getTrainY()).bedY();
    }

    /**
     * Classify the chunk at {@code (chunkX, chunkZ)} — {@link Kind#ISLAND} with probability
     * {@code oceanIslandDensity}, else {@link Kind#OCEAN}. A density {@code <= 0} — or the band being off —
     * always returns {@link Kind#OCEAN} (open water, no island). Callers gate on {@link #isInBand} first;
     * this only decides island-vs-water for a chunk already known to be in the band.
     */
    public static Kind kindOf(ServerLevel overworld, int chunkX, int chunkZ) {
        if (!DungeonTrainCommonConfig.isOceanEnabled()) return Kind.OCEAN;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.oceanLen() <= 0L) return Kind.OCEAN;
        double islandDensity = cycle.oceanIslandDensity();
        if (islandDensity <= 0.0) return Kind.OCEAN;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return Kind.OCEAN;
        return cachedKind(data.getGenerationSeed(), chunkX, chunkZ, islandDensity);
    }

    /** True if the chunk at {@code (chunkX, chunkZ)} carries an island (rather than open water). */
    public static boolean isIslandChunk(ServerLevel overworld, int chunkX, int chunkZ) {
        return kindOf(overworld, chunkX, chunkZ) == Kind.ISLAND;
    }

    /**
     * Pure island test for a world column ({@code blockX, blockZ}) given the world seed + island density —
     * no {@link ServerLevel}, so the biome-source mixin (which only has the published context) can classify
     * without a level lookup. Uses the same per-chunk gate as {@link #kindOf}.
     */
    public static boolean isIslandColumn(long seed, int blockX, int blockZ, double islandDensity) {
        if (islandDensity <= 0.0) return false;
        return classify(seed, blockX >> 4, blockZ >> 4, islandDensity) == Kind.ISLAND;
    }

    /**
     * Pure per-chunk classification (no Minecraft types) — a chunk is an island with probability
     * {@code islandDensity}, else open ocean. Deterministic in {@code (seed, chunkX, chunkZ)} and uniform,
     * so the island fraction tracks its knob linearly. Package-private for unit testing.
     */
    static Kind classify(long seed, int chunkX, int chunkZ, double islandDensity) {
        return hash01(seed, chunkX, chunkZ, ISLAND_SALT) < islandDensity ? Kind.ISLAND : Kind.OCEAN;
    }

    // ---- per-chunk classification cache (mirrors ChuncksBand) ----------------
    private static final ConcurrentHashMap<Long, Kind> KIND_CACHE = new ConcurrentHashMap<>();
    private static volatile long cacheSeed = Long.MIN_VALUE;
    private static final int MAX_CACHE = 1 << 18;

    private static Kind cachedKind(long seed, int chunkX, int chunkZ, double islandDensity) {
        if (seed != cacheSeed) {
            KIND_CACHE.clear();
            cacheSeed = seed;
        }
        long key = ChunkPos.asLong(chunkX, chunkZ);
        Kind hit = KIND_CACHE.get(key);
        if (hit != null) return hit;
        Kind k = classify(seed, chunkX, chunkZ, islandDensity);
        if (KIND_CACHE.size() >= MAX_CACHE) KIND_CACHE.clear();
        KIND_CACHE.put(key, k);
        return k;
    }

    /**
     * Drop the memoised per-chunk kinds. Called on COMMON {@code ModConfigEvent} (the band's position or
     * island density may have changed), alongside {@link WorldGenCycle#invalidateCache()}.
     */
    public static void invalidateCache() {
        KIND_CACHE.clear();
        cacheSeed = Long.MIN_VALUE;
    }

    /**
     * Fluid-containment query for {@code FlowingFluidOceanMixin}: {@code true} if a fluid spread from
     * {@code (fromX, fromY)} to {@code (toX, toY, toZ)} must be <b>vetoed</b> to keep the raised sea
     * static and the corridor dry. Freezes the slab at its three open faces:
     *
     * <ul>
     *   <li><b>Corridor</b> — the destination is an in-band cell inside the corridor Z-span (below the
     *       surface): keeps a dry channel for the train through the sea.</li>
     *   <li><b>Longitudinal edge</b> — the source is in-band raised water and the destination leaves the
     *       band (into the plain-overworld gap), where terrain only reaches vanilla sea level.</li>
     *   <li><b>Above surface</b> — the source is in-band raised water and the destination is at/above the
     *       water surface (no climbing above the intended waterline).</li>
     * </ul>
     *
     * <p>Cheap-outs first: a spread with neither endpoint in the band (the overwhelming majority) returns
     * {@code false} before any {@link DungeonTrainWorldData} lookup. Only genuinely band-adjacent spreads
     * pay the geometry resolution.</p>
     */
    public static boolean vetoSpread(ServerLevel overworld, int fromX, int fromY, int toX, int toY, int toZ) {
        if (startX(overworld) == OFF) return false;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        boolean toInBand = cycle.isInOceanBand(toX);
        boolean fromInBand = cycle.isInOceanBand(fromX);
        if (!toInBand && !fromInBand) return false;                 // fully outside the band → vanilla

        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        TrackGeometry g = TrackGeometry.from(data.dims(), data.getTrainY());
        int surface = g.bedY();

        // Corridor dryness: never let water into the corridor Z-span (wall to wall) below the surface.
        if (toInBand && toY < surface) {
            TunnelGeometry tg = TunnelGeometry.from(g);
            if (toZ >= tg.wallMinZ() && toZ <= tg.wallMaxZ()) return true;
        }
        // Slab containment: band raised water may not escape the band, nor climb above the surface.
        if (fromInBand && fromY < surface) {
            if (!toInBand) return true;                             // longitudinal edge leak
            if (toY >= surface) return true;                        // climbing above the waterline
        }
        return false;
    }

    // ---- per-chunk uniform hash (same idiom as ChuncksBand#hash01) -----------
    private static final int ISLAND_SALT = 1;

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
