package games.brennan.dungeontrain.worldgen.legacy.indev;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The Indev floating band's world, tiled out of finite {@link IndevFloatingLevel}s: the XZ plane is cut
 * into {@link IndevFloatingLevel#WIDTH}×{@link IndevFloatingLevel#LENGTH} tiles aligned to the world grid,
 * and each tile is its own seeded Indev level. Indev's edge falloff empties every level's rim, so
 * neighbouring levels are separated by open void.
 *
 * <p><b>Build once, share.</b> A level is generated whole (see {@link IndevFloatingLevel}), so the first
 * worldgen worker to need a tile builds it and every other worker asking for the same tile waits on the
 * same future — no duplicate builds, no partial reads. Builders never wait on anything themselves, so the
 * wait cannot deadlock. Finished levels are immutable; the LRU cache ({@link #MAX_TILES} levels, ~8 MB
 * each) is the only shared mutable state and is guarded by one lock.</p>
 */
public final class IndevLevels {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Tiles kept resident: the train's corridor plus its view distance spans a handful at a time. */
    static final int MAX_TILES = 6;

    private final long seed;
    private final Object lock = new Object();
    private final LinkedHashMap<Long, CompletableFuture<IndevFloatingLevel>> tiles =
            new LinkedHashMap<>(16, 0.75f, true);

    public IndevLevels(long seed) {
        this.seed = seed;
    }

    public long seed() {
        return seed;
    }

    /** The level containing chunk {@code (chunkX, chunkZ)} — a chunk never straddles two tiles. */
    public IndevFloatingLevel levelForChunk(int chunkX, int chunkZ) {
        return level(Math.floorDiv(chunkX << 4, IndevFloatingLevel.WIDTH), Math.floorDiv(chunkZ << 4, IndevFloatingLevel.LENGTH));
    }

    /** The level for tile {@code (tileX, tileZ)}, building it on this thread if nobody has yet. */
    public IndevFloatingLevel level(int tileX, int tileZ) {
        long key = ((long) tileX << 32) | (tileZ & 0xFFFFFFFFL);
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
        return future.join();
    }

    /** Drop least-recently-used finished levels past the cap (an in-flight build is never dropped). */
    private void evict() {
        Iterator<Map.Entry<Long, CompletableFuture<IndevFloatingLevel>>> it = tiles.entrySet().iterator();
        while (tiles.size() > MAX_TILES && it.hasNext()) {
            if (it.next().getValue().isDone()) it.remove();
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
