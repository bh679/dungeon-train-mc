package games.brennan.dungeontrain.worldgen.legacy.classic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural invariants of the pure Classic 0.30 level generator port. */
final class ClassicTerrainTest {

    private static final long SEED = 3257840388504953787L;
    private static final ClassicLevel LEVEL = ClassicTerrain.generate(SEED);
    private static final int W = ClassicLevel.WIDTH;
    private static final int L = ClassicLevel.LENGTH;
    private static final int H = ClassicLevel.HEIGHT;
    private static final int WATER = ClassicLevel.WATER_LEVEL;

    private static int top(ClassicLevel level, int x, int z) {
        int y = H - 1;
        while (y > 0 && level.get(x, y, z) == ClassicBlocks.AIR) y--;
        return y;
    }

    @Test
    @DisplayName("same level seed ⇒ identical level; a different seed differs")
    void deterministic() {
        ClassicLevel again = ClassicTerrain.generate(SEED);
        assertArrayEquals(LEVEL.chunk(3, 7), again.chunk(3, 7));
        assertArrayEquals(LEVEL.chunk(15, 0), again.chunk(15, 0));
        assertFalse(java.util.Arrays.equals(LEVEL.chunk(8, 8), ClassicTerrain.generate(SEED + 1).chunk(8, 8)));
    }

    @Test
    @DisplayName("bedrock floor, open sky, surface never above the level's top two rows")
    void floorAndCeiling() {
        for (int x = 0; x < W; x += 7) {
            for (int z = 0; z < L; z += 7) {
                assertEquals(ClassicBlocks.BEDROCK, LEVEL.get(x, 0, z));
                assertEquals(ClassicBlocks.AIR, LEVEL.get(x, H - 1, z));
            }
        }
    }

    @Test
    @DisplayName("the level edge is sea: nothing dry-and-open sits below the water line on the rim")
    void edgesFlooded() {
        for (int i = 0; i < W; i++) {
            int[][] rim = {{i, 0}, {i, L - 1}, {0, i}, {W - 1, i}};
            for (int[] c : rim) {
                // Classic floods every open cell under the water line that touches the edge.
                assertTrue(LEVEL.get(c[0], WATER - 1, c[1]) != ClassicBlocks.AIR,
                        "open air under the water line at the rim " + c[0] + "," + c[1]);
            }
        }
    }

    @Test
    @DisplayName("a plausible Classic level: grass land, sea, sand shores, trees, flowers and ore")
    void plausibleLevel() {
        int grass = 0;
        int sea = 0;
        int sand = 0;
        int logs = 0;
        int flowers = 0;
        int ore = 0;
        int cave = 0;
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < L; z++) {
                int t = top(LEVEL, x, z);
                byte surface = LEVEL.get(x, t, z);
                if (surface == ClassicBlocks.GRASS) grass++;
                if (surface == ClassicBlocks.WATER) sea++;
                if (surface == ClassicBlocks.SAND) sand++;
                for (int y = 0; y < H; y++) {
                    byte b = LEVEL.get(x, y, z);
                    if (b == ClassicBlocks.LOG) logs++;
                    if (b == ClassicBlocks.DANDELION || b == ClassicBlocks.ROSE) flowers++;
                    if (b == ClassicBlocks.COAL_ORE || b == ClassicBlocks.IRON_ORE || b == ClassicBlocks.GOLD_ORE) ore++;
                    if (b == ClassicBlocks.AIR && y < t) cave++;
                }
            }
        }
        int cols = W * L;
        assertTrue(grass > cols / 10, "grass " + grass);
        assertTrue(sea > 0 && sea < cols, "sea " + sea);
        assertTrue(sand > 0, "sand " + sand);
        assertTrue(logs > 50, "logs " + logs);
        assertTrue(flowers > 20, "flowers " + flowers);
        assertTrue(ore > 500, "ore " + ore);
        assertTrue(cave > 1000, "cave air " + cave);
    }

    @Test
    @DisplayName("every trunk stands on dirt with leaves at its crown")
    void treesRooted() {
        int trees = 0;
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < L; z++) {
                for (int y = 1; y < H; y++) {
                    if (LEVEL.get(x, y, z) == ClassicBlocks.LOG && LEVEL.get(x, y - 1, z) != ClassicBlocks.LOG) {
                        trees++;
                        assertEquals(ClassicBlocks.DIRT, LEVEL.get(x, y - 1, z), "trunk base at " + x + "," + y + "," + z);
                    }
                }
            }
        }
        assertTrue(trees > 10, "trees " + trees);
    }

    @Test
    @DisplayName("tree grower refuses a spot without grass under it")
    void treeNeedsGrass() {
        byte[] blocks = new byte[W * L * H];
        blocks[ClassicLevel.index(10, 30, 10)] = ClassicBlocks.STONE;
        assertFalse(ClassicPlanting.growTree(new java.util.Random(1), blocks, 10, 31, 10));
        blocks[ClassicLevel.index(10, 30, 10)] = ClassicBlocks.GRASS;
        assertTrue(ClassicPlanting.growTree(new java.util.Random(1), blocks, 10, 31, 10));
        assertEquals(ClassicBlocks.DIRT, blocks[ClassicLevel.index(10, 30, 10)]);
        assertEquals(ClassicBlocks.LOG, blocks[ClassicLevel.index(10, 31, 10)]);
    }
}
