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
    @DisplayName("the track (Z 0) runs down the middle of a level, not a border strip")
    void trackCentred() {
        assertFalse(ClassicLevels.isBorder(0, 0));
        assertEquals(ClassicLevel.LENGTH / 2, ClassicLevels.localZ(0));
        for (int z = -8; z <= 8; z++) assertFalse(ClassicLevels.isBorder(100, z));
    }

    @Test
    @DisplayName("tiles repeat every PITCH blocks: a level, then a border strip")
    void tilePattern() {
        assertFalse(ClassicLevels.isBorder(0, 0));
        assertFalse(ClassicLevels.isBorder(ClassicLevel.WIDTH - 1, 0));
        assertTrue(ClassicLevels.isBorder(ClassicLevel.WIDTH, 0));
        assertTrue(ClassicLevels.isBorder(ClassicLevels.PITCH - 1, 0));
        assertFalse(ClassicLevels.isBorder(ClassicLevels.PITCH, 0));
        assertTrue(ClassicLevels.isBorder(-1, 0));
        assertEquals(-1, ClassicLevels.tileX(-1));
        assertEquals(1, ClassicLevels.tileX(ClassicLevels.PITCH));
        assertTrue(ClassicLevels.isBorder(0, ClassicLevel.LENGTH / 2));
        assertTrue(ClassicLevels.isBorder(0, -ClassicLevel.LENGTH / 2 - 1));
    }

    @Test
    @DisplayName("every chunk is wholly level or wholly border")
    void chunkAligned() {
        for (int cx = -40; cx < 40; cx++) {
            for (int cz = -40; cz < 40; cz++) {
                boolean border = ClassicLevels.isBorder(cx << 4, cz << 4);
                assertEquals(border, ClassicLevels.isBorder((cx << 4) + 15, (cz << 4) + 15), cx + "," + cz);
                assertEquals(border, ClassicLevels.isBorder((cx << 4) + 15, cz << 4), cx + "," + cz);
            }
        }
    }

    @Test
    @DisplayName("top water lands on world y 62, flush with the modern sea")
    void seaAligned() {
        assertEquals(62, ClassicLevels.Y_OFFSET + ClassicLevel.WATER_LEVEL - 1);
    }

    @Test
    @DisplayName("border chunk: bedrock floor two below the water line, then two rows of water")
    void borderColumn() {
        byte[] col = noPrefetch().chunkColumn(ClassicLevel.WIDTH >> 4, 0);
        int h = ClassicLevel.HEIGHT;
        for (int c = 0; c < 256; c++) {
            assertEquals(ClassicBlocks.BEDROCK, col[c * h]);
            assertEquals(ClassicBlocks.BEDROCK, col[c * h + ClassicLevel.WATER_LEVEL - 3]);
            assertEquals(ClassicBlocks.WATER, col[c * h + ClassicLevel.WATER_LEVEL - 2]);
            assertEquals(ClassicBlocks.WATER, col[c * h + ClassicLevel.WATER_LEVEL - 1]);
            assertEquals(ClassicBlocks.AIR, col[c * h + ClassicLevel.WATER_LEVEL]);
        }
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
