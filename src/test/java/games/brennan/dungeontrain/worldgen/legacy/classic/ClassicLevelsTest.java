package games.brennan.dungeontrain.worldgen.legacy.classic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tile layout and the build-once level cache behind the Classic band. */
final class ClassicLevelsTest {

    private static final long SEED = 0xC1A55L;

    private static ClassicLevels noPrefetch() {
        return new ClassicLevels(SEED, r -> { });
    }

    @Test
    @DisplayName("the track (Z 0) runs down the middle of a level row, not a seam")
    void trackCentred() {
        assertEquals(ClassicLevel.LENGTH / 2, ClassicLevels.localZ(0));
        assertEquals(0, ClassicLevels.tileZ(-ClassicLevel.LENGTH / 2));
        assertEquals(-1, ClassicLevels.tileZ(-ClassicLevel.LENGTH / 2 - 1));
        assertEquals(1, ClassicLevels.tileZ(ClassicLevel.LENGTH / 2));
    }

    @Test
    @DisplayName("levels sit edge to edge: tiles repeat every 256 blocks with no gap")
    void tilePattern() {
        assertEquals(ClassicLevel.WIDTH, ClassicLevels.PITCH);
        assertEquals(0, ClassicLevels.tileX(0));
        assertEquals(0, ClassicLevels.tileX(ClassicLevel.WIDTH - 1));
        assertEquals(1, ClassicLevels.tileX(ClassicLevel.WIDTH));
        assertEquals(-1, ClassicLevels.tileX(-1));
        assertEquals(ClassicLevel.WIDTH - 1, ClassicLevels.localX(-1));
        assertEquals(0, ClassicLevels.localX(ClassicLevel.WIDTH));
    }

    @Test
    @DisplayName("every chunk maps to exactly one tile")
    void chunkAligned() {
        for (int cx = -40; cx < 40; cx++) {
            for (int cz = -40; cz < 40; cz++) {
                int x = cx << 4;
                int z = cz << 4;
                assertEquals(ClassicLevels.tileX(x), ClassicLevels.tileX(x + 15), cx + "," + cz);
                assertEquals(ClassicLevels.tileZ(z), ClassicLevels.tileZ(z + 15), cx + "," + cz);
            }
        }
    }

    @Test
    @DisplayName("chunks either side of a tile edge come from different levels")
    void neighboursAcrossSeam() {
        ClassicLevels levels = noPrefetch();
        int lastChunk = (ClassicLevel.WIDTH >> 4) - 1;
        byte[] left = levels.chunkColumn(lastChunk, 0);
        byte[] right = levels.chunkColumn(lastChunk + 1, 0);
        assertArrayEquals(levels.level(0, 0).chunk(lastChunk, ClassicLevel.LENGTH / 2 >> 4), left);
        assertArrayEquals(levels.level(1, 0).chunk(0, ClassicLevel.LENGTH / 2 >> 4), right);
    }

    @Test
    @DisplayName("top water lands on world y 62, flush with the modern sea")
    void seaAligned() {
        assertEquals(62, ClassicLevels.Y_OFFSET + ClassicLevel.WATER_LEVEL - 1);
    }

    @Test
    @DisplayName("each tile is its own level; a level chunk is that level's slice")
    void tilesDiffer() {
        assertNotEquals(ClassicLevels.levelSeed(SEED, 0, 0), ClassicLevels.levelSeed(SEED, 1, 0));
        assertNotEquals(ClassicLevels.levelSeed(SEED, 0, 0), ClassicLevels.levelSeed(SEED, 0, 1));
        ClassicLevels levels = noPrefetch();
        ClassicLevel level = ClassicTerrain.generate(ClassicLevels.levelSeed(SEED, 0, 0));
        // chunk (2, -8) → level-local chunk (2, 0) of tile (0, 0)
        assertArrayEquals(level.chunk(2, 0), levels.chunkColumn(2, -8));
    }

    @Test
    @DisplayName("eight threads asking for one tile build it once and share it")
    void buildsOnce() throws Exception {
        ClassicLevels levels = noPrefetch();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<ClassicLevel>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return levels.level(4, -2);
                }));
            }
            go.countDown();
            ClassicLevel first = results.get(0).get();
            for (Future<ClassicLevel> f : results) assertSame(first, f.get());
            assertEquals(1, levels.cachedCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the cache stays bounded; an evicted level rebuilds identically")
    void bounded() {
        ClassicLevels levels = noPrefetch();
        byte[] before = levels.level(0, 0).chunk(5, 5);
        for (int t = 1; t <= ClassicLevels.MAX_LEVELS + 2; t++) levels.level(t, 0);
        assertTrue(levels.cachedCount() <= ClassicLevels.MAX_LEVELS);
        assertArrayEquals(before, levels.level(0, 0).chunk(5, 5));
    }

    @Test
    @DisplayName("first demand for a tile prefetches the next tile along +X")
    void prefetchesAhead() {
        List<Runnable> queued = new ArrayList<>();
        ClassicLevels levels = new ClassicLevels(SEED, queued::add);
        levels.level(0, 0);
        assertEquals(1, queued.size());
        levels.level(0, 0);
        assertEquals(1, queued.size(), "a repeat demand does not prefetch again");
        queued.get(0).run();
        assertEquals(2, levels.cachedCount());
    }
}
