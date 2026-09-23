package games.brennan.dungeontrain.worldgen.legacy.classic;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

/**
 * How the Classic band lays finite Classic levels over an endless world, and the per-seed cache of them.
 *
 * <p><b>Tiling.</b> The band is a grid of {@link ClassicLevel}s ({@code 256 × 256}) laid edge to edge on
 * both axes. Tile X is anchored at world X 0; tile Z is shifted by {@link #Z_SHIFT} so the track (at Z 0)
 * runs down the middle of a level row, not along a seam. Both are chunk-aligned, so every chunk belongs to
 * exactly one level. Each tile is its own level with its own seed — each floods in from its own edges, so
 * seams show as shorelines and cliffs where two levels meet.</p>
 *
 * <p><b>Cache / thread-safety.</b> A level must be built whole before any chunk of it can be written, and
 * every chunk of it asks for the same level from whichever worldgen worker it lands on. The first asker
 * installs a {@link CompletableFuture} and builds the level on its own thread (outside the map lock);
 * the rest wait on the future. Levels are immutable, bounded to {@link #MAX_LEVELS} (≈4 MB each) and
 * evicted oldest first — deterministic, so an evicted level rebuilds byte-identically. The first demand
 * for a tile also builds the next tile along +X in the background, so the train rarely waits.</p>
 */
public final class ClassicLevels {

    /** Tile pitch: levels sit edge to edge. */
    public static final int PITCH = ClassicLevel.WIDTH;
    /** Z shift that centres a level row on the track (Z 0). */
    public static final int Z_SHIFT = ClassicLevel.LENGTH / 2;
    /**
     * World Y of the level's {@code y = 0}: the level's top water (level y {@code WATER_LEVEL - 1}) lands on
     * y 62, flush with the modern sea.
     */
    public static final int Y_OFFSET = 63 - ClassicLevel.WATER_LEVEL;

    static final int MAX_LEVELS = 8;

    private final long seed;
    private final Executor prefetchExecutor;
    private final ConcurrentHashMap<Long, CompletableFuture<ClassicLevel>> levels = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> order = new ConcurrentLinkedQueue<>();
    private final java.util.Set<Long> demanded = ConcurrentHashMap.newKeySet();

    public ClassicLevels(long seed, Executor prefetchExecutor) {
        this.seed = seed;
        this.prefetchExecutor = prefetchExecutor;
    }

    public long seed() {
        return seed;
    }

    // ---- tile math -----------------------------------------------------------------------------------

    public static int tileX(int worldX) {
        return Math.floorDiv(worldX, PITCH);
    }

    public static int tileZ(int worldZ) {
        return Math.floorDiv(worldZ + Z_SHIFT, PITCH);
    }

    /** Level-local X of {@code worldX}. */
    public static int localX(int worldX) {
        return Math.floorMod(worldX, PITCH);
    }

    /** Level-local Z of {@code worldZ}. */
    public static int localZ(int worldZ) {
        return Math.floorMod(worldZ + Z_SHIFT, PITCH);
    }

    // ---- chunk columns -------------------------------------------------------------------------------

    /**
     * The block column for chunk {@code (chunkX, chunkZ)} in the chunk-writer layout
     * ({@code (x·16 + z)·HEIGHT + y}). May block while the chunk's level is built.
     */
    public byte[] chunkColumn(int chunkX, int chunkZ) {
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        ClassicLevel level = level(tileX(minX), tileZ(minZ));
        return level.chunk(localX(minX) >> 4, localZ(minZ) >> 4);
    }

    /** The level for tile {@code (tileX, tileZ)}, building it (once, across all threads) if needed. */
    public ClassicLevel level(int tileX, int tileZ) {
        long key = key(tileX, tileZ);
        if (demanded.add(key)) prefetch(tileX + 1, tileZ);
        return obtain(tileX, tileZ, key).join();
    }

    private void prefetch(int tileX, int tileZ) {
        long key = key(tileX, tileZ);
        if (levels.containsKey(key)) return;
        try {
            prefetchExecutor.execute(() -> obtain(tileX, tileZ, key));
        } catch (RuntimeException rejected) {
            // Executor shutting down — the level just builds on demand instead.
        }
    }

    private CompletableFuture<ClassicLevel> obtain(int tileX, int tileZ, long key) {
        CompletableFuture<ClassicLevel> existing = levels.get(key);
        if (existing != null) return existing;
        CompletableFuture<ClassicLevel> mine = new CompletableFuture<>();
        existing = levels.putIfAbsent(key, mine);
        if (existing != null) return existing;
        order.add(key);
        evictOverflow();
        try {
            mine.complete(ClassicTerrain.generate(levelSeed(seed, tileX, tileZ)));
        } catch (Throwable t) {
            levels.remove(key, mine);
            mine.completeExceptionally(t);
        }
        return mine;
    }

    private void evictOverflow() {
        while (levels.size() > MAX_LEVELS) {
            Long oldest = order.poll();
            if (oldest == null) return;
            levels.remove(oldest);
            demanded.remove(oldest);
        }
    }

    /** Levels currently held (built or building). Package-private for tests. */
    int cachedCount() {
        return levels.size();
    }

    /** Drop every cached level. */
    public void clear() {
        levels.clear();
        order.clear();
        demanded.clear();
    }

    // ---- seeds ---------------------------------------------------------------------------------------

    private static long key(int tileX, int tileZ) {
        return ((long) tileX << 32) | (tileZ & 0xFFFFFFFFL);
    }

    /** The level seed of tile {@code (tileX, tileZ)}: splitmix64 of the world seed and tile. */
    static long levelSeed(long seed, int tileX, int tileZ) {
        long h = seed ^ 0x436C61737369634CL; // "ClassicL"
        h = mix(h + (long) tileX * 0x9E3779B97F4A7C15L);
        h = mix(h + (long) tileZ * 0xC2B2AE3D27D4EB4FL);
        return h;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
