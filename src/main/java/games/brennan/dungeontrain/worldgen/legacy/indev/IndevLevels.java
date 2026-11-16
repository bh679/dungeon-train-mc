package games.brennan.dungeontrain.worldgen.legacy.indev;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The Indev floating band's world, tiled out of finite {@link IndevFloatingLevel}s: the XZ plane is cut
 * into {@link IndevFloatingLevel#WIDTH}×{@link IndevFloatingLevel#LENGTH} tiles, and each tile is its own
 * seeded Indev level. Indev's edge falloff empties every level's rim, so neighbouring levels are separated
 * by open void. The grid is shifted half a tile in Z ({@link #Z_SHIFT}) so the train's corridor, which runs
 * along {@code z ≈ 0}, passes through the middle of each level — where every layer has islands — rather
 * than along the empty seam between two rows of levels.
 *
 * <p><b>Build once, share.</b> A level is generated whole (see {@link IndevFloatingLevel}), so the first
 * worldgen worker to need a tile builds it and every other worker asking for the same tile waits on the
 * same future — no duplicate builds, no partial reads. Builders never wait on anything themselves, so the
 * wait cannot deadlock. Finished levels are immutable; the LRU cache ({@link #MAX_TILES} levels, ~8 MB
 * each) is the only shared mutable state and is guarded by one lock.</p>
 *
 * <p><b>Prefetch.</b> A level takes ~0.75 s to build, and every worker that needs a chunk of it waits that
 * long. The first demand for a tile therefore builds the next tile along +X on {@code prefetchExecutor},
 * as {@link games.brennan.dungeontrain.worldgen.legacy.classic.ClassicLevels} does, so the train's ring of
 * chunks usually finds its level ready.</p>
 */
public final class IndevLevels {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Tiles kept resident: the train's corridor spans three tile rows, and up to two columns of them are
     * still generating while the prefetch adds a third ahead.
     */
    static final int MAX_TILES = 9;

    private final long seed;
    private final Executor prefetchExecutor;
    private final Object lock = new Object();
    private final LinkedHashMap<Long, CompletableFuture<IndevFloatingLevel>> tiles =
            new LinkedHashMap<>(16, 0.75f, true);
    /** Tiles a chunk has asked for (not just prefetched); only a demand prefetches, so prefetch never chains. */
    private final Set<Long> demanded = new HashSet<>();

    public IndevLevels(long seed, Executor prefetchExecutor) {
        this.seed = seed;
        this.prefetchExecutor = prefetchExecutor;
    }

    public long seed() {
        return seed;
    }

    /** Blocks the tile grid is shifted along Z: level centres sit on {@code z = 0}. A multiple of 16. */
    public static final int Z_SHIFT = IndevFloatingLevel.LENGTH / 2;

    /** The level containing chunk {@code (chunkX, chunkZ)} — a chunk never straddles two tiles. */
    public IndevFloatingLevel levelForChunk(int chunkX, int chunkZ) {
        return level(Math.floorDiv(chunkX << 4, IndevFloatingLevel.WIDTH), Math.floorDiv((chunkZ << 4) + Z_SHIFT, IndevFloatingLevel.LENGTH));
    }

    /** Level-local X of world block {@code x}. */
    public static int localX(int x) {
        return Math.floorMod(x, IndevFloatingLevel.WIDTH);
    }

    /** Level-local Z of world block {@code z}. */
    public static int localZ(int z) {
        return Math.floorMod(z + Z_SHIFT, IndevFloatingLevel.LENGTH);
    }

    /** The level for tile {@code (tileX, tileZ)}, building it on this thread if nobody has yet. */
    public IndevFloatingLevel level(int tileX, int tileZ) {
        long key = key(tileX, tileZ);
        boolean first;
        synchronized (lock) {
            first = demanded.add(key);
        }
        if (first) prefetch(tileX + 1, tileZ);
        return obtain(tileX, tileZ, key).join();
    }

    private void prefetch(int tileX, int tileZ) {
        long key = key(tileX, tileZ);
        synchronized (lock) {
            if (tiles.containsKey(key)) return;
        }
        try {
            prefetchExecutor.execute(() -> obtain(tileX, tileZ, key));
        } catch (RuntimeException rejected) {
            // Executor shutting down — the level just builds on demand instead.
        }
    }

    /** The tile's future, building the level on this thread first if nobody has started it. */
    private CompletableFuture<IndevFloatingLevel> obtain(int tileX, int tileZ, long key) {
        CompletableFuture<IndevFloatingLevel> future;
        boolean build = false;
        synchronized (lock) {
            future = tiles.get(key);
            if (future == null) {
                future = new CompletableFuture<>();
                tiles.put(key, future);
                build = true;
                evict();
            }
        }
        if (build) {
            try {
                long t0 = System.nanoTime();
                future.complete(new IndevFloatingLevel(tileSeed(seed, tileX, tileZ)));
                LOGGER.debug("[DungeonTrain] indev floating level ({}, {}) built in {} ms",
                        tileX, tileZ, (System.nanoTime() - t0) / 1_000_000L);
            } catch (Throwable t) {
                synchronized (lock) {
                    tiles.remove(key, future);
                }
                future.completeExceptionally(t);
            }
        }
        return future;
    }

    private static long key(int tileX, int tileZ) {
        return ((long) tileX << 32) | (tileZ & 0xFFFFFFFFL);
    }

    /**
     * Drop least-recently-used finished levels past the cap (an in-flight build is never dropped). A dropped
     * tile forgets its demand too, so coming back to it prefetches again.
     */
    private void evict() {
        Iterator<Map.Entry<Long, CompletableFuture<IndevFloatingLevel>>> it = tiles.entrySet().iterator();
        while (tiles.size() > MAX_TILES && it.hasNext()) {
            Map.Entry<Long, CompletableFuture<IndevFloatingLevel>> e = it.next();
            if (e.getValue().isDone()) {
                demanded.remove(e.getKey());
                it.remove();
            }
        }
    }

    /** Resident tile count (tests). */
    int residentTiles() {
        synchronized (lock) {
            return tiles.size();
        }
    }

    /** Per-tile level seed: a splitmix64-style mix of the world seed and the tile coordinates. */
    public static long tileSeed(long seed, int tileX, int tileZ) {
        long h = seed * 0x9E3779B97F4A7C15L + 0x1D5B1E5L;
        h ^= (long) tileX * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h ^= (long) tileZ * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
