package games.brennan.dungeontrain.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How much a photo is enlarged for Discord, and that enlarging keeps every pixel a clean square. */
class PhotoUpscaleTest {

    @Test
    @DisplayName("a small photo is scaled by a whole factor to about 1024 on its long edge")
    void factors() {
        assertEquals(16, PhotoUpscale.factorFor(64, 64));     // 1024
        assertEquals(4, PhotoUpscale.factorFor(320, 320));    // 1280
        assertEquals(2, PhotoUpscale.factorFor(640, 360));    // 1280 x 720
        assertEquals(2, PhotoUpscale.factorFor(360, 640));    // the long edge decides, either way round
    }

    @Test
    @DisplayName("a photo already large, or a nonsense size, is left alone")
    void noScale() {
        assertEquals(1, PhotoUpscale.factorFor(1024, 768));
        assertEquals(1, PhotoUpscale.factorFor(2000, 100));
        assertEquals(1, PhotoUpscale.factorFor(0, 0));
    }

    @Test
    @DisplayName("the result never passes the cap")
    void capped() {
        for (int edge = 1; edge < 1024; edge++) {
            assertTrue(edge * PhotoUpscale.factorFor(edge, 1) <= PhotoUpscale.MAX_LONG_EDGE, "edge " + edge);
        }
    }

    @Test
    @DisplayName("each pixel becomes a factor-by-factor block, rows and columns in order")
    void nearestBlocks() {
        byte[] src = {1, 2,
                      3, 4};
        byte[] out = PhotoUpscale.nearest(src, 2, 2, 2);
        assertArrayEquals(new byte[] {
                1, 1, 2, 2,
                1, 1, 2, 2,
                3, 3, 4, 4,
                3, 3, 4, 4}, out);
    }

    @Test
    @DisplayName("a non-square photo scales without mixing rows")
    void nonSquare() {
        byte[] src = {5, 6, 7};                       // 3 wide, 1 tall
        assertArrayEquals(new byte[] {5, 5, 6, 6, 7, 7,
                                      5, 5, 6, 6, 7, 7}, PhotoUpscale.nearest(src, 3, 1, 2));
    }

    @Test
    @DisplayName("factor 1 returns a copy, never the caller's array")
    void factorOneCopies() {
        byte[] src = {9, 8};
        byte[] out = PhotoUpscale.nearest(src, 2, 1, 1);
        assertArrayEquals(src, out);
        assertNotSame(src, out);
    }
}
