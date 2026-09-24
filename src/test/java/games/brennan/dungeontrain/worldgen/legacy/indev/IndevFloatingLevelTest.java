package games.brennan.dungeontrain.worldgen.legacy.indev;

import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBlocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the Indev floating level port and its tile cache. */
final class IndevFloatingLevelTest {

    private static final long SEED = IndevLevels.tileSeed(0x1D5EEDL, 3, -2);
    private static IndevFloatingLevel level;

    @BeforeAll
    static void build() {
        level = new IndevFloatingLevel(SEED);
    }

    @Test
    @DisplayName("nibble packing round-trips every block id the level uses")
    void packRoundTrip() {
        byte[] raw = {BetaBlocks.AIR, BetaBlocks.STONE, BetaBlocks.GRASS, BetaBlocks.DIRT, BetaBlocks.SAND, BetaBlocks.GRAVEL, BetaBlocks.STONE};
        byte[] packed = IndevFloatingLevel.pack(raw);
        assertEquals(4, packed.length);
        byte[] back = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            int b = packed[i >> 1];
            back[i] = (byte) ((i & 1) == 0 ? b & 0x0F : (b >> 4) & 0x0F);
        }
        assertArrayEquals(raw, back);
    }

    @Test
    @DisplayName("the same tile seed rebuilds the same level; a different one does not")
    void deterministic() {
        IndevFloatingLevel again = new IndevFloatingLevel(SEED);
        IndevFloatingLevel other = new IndevFloatingLevel(SEED + 1);
        int diffs = 0;
        for (int x = 0; x < IndevFloatingLevel.WIDTH; x += 7) {
            for (int z = 0; z < IndevFloatingLevel.LENGTH; z += 7) {
                for (int y = 0; y < IndevFloatingLevel.HEIGHT; y += 3) {
                    assertEquals(level.block(x, y, z), again.block(x, y, z));
                    if (level.block(x, y, z) != other.block(x, y, z)) diffs++;
                }
            }
        }
        assertTrue(diffs > 0, "different seeds gave identical levels");
    }

    @Test
    @DisplayName("floating levels hold no fluid, bedrock or anything off the island palette")
    void palette() {
        Set<Byte> allowed = Set.of(BetaBlocks.AIR, BetaBlocks.STONE, BetaBlocks.DIRT, BetaBlocks.GRASS,
                BetaBlocks.SAND, BetaBlocks.GRAVEL);
        for (int x = 0; x < IndevFloatingLevel.WIDTH; x += 3) {
            for (int z = 0; z < IndevFloatingLevel.LENGTH; z += 3) {
                for (int y = 0; y < IndevFloatingLevel.HEIGHT; y++) {
                    assertTrue(allowed.contains(level.block(x, y, z)), "unexpected block at " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    @DisplayName("the level rim is open void, so neighbouring levels never touch")
    void rimIsVoid() {
        int last = IndevFloatingLevel.WIDTH - 1;
        for (int i = 0; i < IndevFloatingLevel.WIDTH; i++) {
            for (int y = 0; y < IndevFloatingLevel.HEIGHT; y++) {
                assertEquals(BetaBlocks.AIR, level.block(0, y, i));
                assertEquals(BetaBlocks.AIR, level.block(last, y, i));
                assertEquals(BetaBlocks.AIR, level.block(i, y, 0));
                assertEquals(BetaBlocks.AIR, level.block(i, y, last));
            }
        }
    }

    @Test
    @DisplayName("islands sit in several stacked layers over an open floor")
    void stackedLayers() {
        // Bucket every grass block by its 48-block layer band; a floating level fills several.
        Set<Integer> layers = new TreeSet<>();
        int solidAtBottom = 0;
        for (int x = 0; x < IndevFloatingLevel.WIDTH; x += 2) {
            for (int z = 0; z < IndevFloatingLevel.LENGTH; z += 2) {
                if (level.block(x, 0, z) != BetaBlocks.AIR) solidAtBottom++;
                for (int y = 0; y < IndevFloatingLevel.HEIGHT; y++) {
                    if (level.block(x, y, z) == BetaBlocks.GRASS) layers.add((y + 24) / 48);
                }
            }
        }
        assertTrue(layers.size() >= 3, "island layers: " + layers);
        // The lowest layer's deepest island bottoms may just touch the level floor, as in Indev; the floor
        // itself is still essentially open void (no ground plane under the stack).
        int columns = (IndevFloatingLevel.WIDTH / 2) * (IndevFloatingLevel.LENGTH / 2);
        assertTrue(solidAtBottom < columns / 100, "solid floor columns: " + solidAtBottom);
    }

    @Test
    @DisplayName("tile cache: one build per tile, chunks map to their tile, LRU stays capped")
    void tileCache() {
        IndevLevels levels = new IndevLevels(0x1D5EEDL);
        IndevFloatingLevel a = levels.level(3, -2);
        assertSame(a, levels.level(3, -2));
        // Tile (3,-2) spans chunk X 48..63 and, shifted half a tile in Z, chunk Z -40..-25.
        assertSame(a, levels.levelForChunk(3 * 16, -2 * 16 - 8));
        assertSame(a, levels.levelForChunk(3 * 16 + 15, -2 * 16 + 7));
        assertEquals(0, IndevLevels.localZ(-IndevLevels.Z_SHIFT));
        assertEquals(IndevFloatingLevel.LENGTH / 2, IndevLevels.localZ(0));  // z = 0 is a level's middle
        assertEquals(a.block(100, 120, 40), level.block(100, 120, 40)); // same tile seed → same level
        assertNotEquals(IndevLevels.tileSeed(1L, 0, 0), IndevLevels.tileSeed(1L, 0, 1));
        for (int i = 0; i < IndevLevels.MAX_TILES + 2; i++) levels.level(100 + i, 0);
        assertEquals(IndevLevels.MAX_TILES, levels.residentTiles());
    }
}
