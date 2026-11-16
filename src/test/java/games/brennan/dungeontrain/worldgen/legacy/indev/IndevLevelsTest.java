package games.brennan.dungeontrain.worldgen.legacy.indev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Pure tests for {@link IndevLevels}' tile cache and its +X prefetch. Each level build takes ~0.75 s. */
final class IndevLevelsTest {

    private static final long SEED = 0x1D5EEDL;

    private static IndevLevels noPrefetch() {
        return new IndevLevels(SEED, r -> { });
    }

    @Test
    @DisplayName("first demand for a tile prefetches the next tile along +X, once")
    void prefetchesAhead() {
        List<Runnable> queued = new ArrayList<>();
        IndevLevels levels = new IndevLevels(SEED, queued::add);
        levels.level(0, 0);
        assertEquals(1, queued.size());
        levels.level(0, 0);
        assertEquals(1, queued.size(), "a repeat demand does not prefetch again");
        queued.get(0).run();
        assertEquals(2, levels.residentTiles());
        assertEquals(1, queued.size(), "a prefetched tile does not prefetch the one after it");
    }

    @Test
    @DisplayName("a prefetched tile is the one a chunk gets, and it matches an on-demand build")
    void prefetchedTileIsShared() {
        List<Runnable> queued = new ArrayList<>();
        IndevLevels levels = new IndevLevels(SEED, queued::add);
        levels.level(0, 0);
        queued.get(0).run();
        IndevFloatingLevel prefetched = levels.level(1, 0);
        assertEquals(2, levels.residentTiles(), "the demand reused the prefetched build");
        IndevFloatingLevel direct = noPrefetch().level(1, 0);
        for (int x = 0; x < IndevFloatingLevel.WIDTH; x += 5) {
            for (int z = 0; z < IndevFloatingLevel.LENGTH; z += 5) {
                for (int y = 0; y < IndevFloatingLevel.HEIGHT; y += 4) {
                    assertEquals(direct.block(x, y, z), prefetched.block(x, y, z));
                }
            }
        }
        assertSame(prefetched, levels.level(1, 0));
    }

    @Test
    @DisplayName("a refused prefetch falls back to building on demand")
    void rejectedPrefetch() {
        IndevLevels levels = new IndevLevels(SEED, r -> { throw new java.util.concurrent.RejectedExecutionException(); });
        levels.level(0, 0);
        assertEquals(1, levels.residentTiles());
    }

    @Test
    @DisplayName("eight threads asking for one tile build it once and share it")
    void buildsOnce() throws Exception {
        IndevLevels levels = noPrefetch();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<IndevFloatingLevel>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return levels.level(4, -2);
                }));
            }
            go.countDown();
            IndevFloatingLevel first = results.get(0).get();
            for (Future<IndevFloatingLevel> f : results) assertSame(first, f.get());
            assertEquals(1, levels.residentTiles());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("an evicted tile forgets its demand, so coming back prefetches again")
    void evictionResetsDemand() {
        List<Runnable> queued = new ArrayList<>();
        IndevLevels levels = new IndevLevels(SEED, queued::add);
        levels.level(0, 0);
        for (int t = 1; t <= IndevLevels.MAX_TILES; t++) levels.level(100 + t, 0);
        assertEquals(IndevLevels.MAX_TILES, levels.residentTiles());
        int before = queued.size();
        levels.level(0, 0);
        assertEquals(before + 1, queued.size());
    }

    @Test
    @DisplayName("the level for a negative tile key is distinct from its positive twin")
    void keysDoNotCollide() {
        IndevLevels levels = noPrefetch();
        IndevFloatingLevel a = levels.level(-1, 0);
        IndevFloatingLevel b = levels.level(0, -1);
        assertEquals(2, levels.residentTiles());
        assertNotSame(a, b);
    }
}
