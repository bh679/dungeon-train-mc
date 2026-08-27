package games.brennan.dungeontrain.client.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry guard for the 2×2 picker grid across the GUI scales players actually use.
 *
 * <p>At scale 1 a 1080p window is ~1920×1080 GUI pixels; at scale 4 it is ~480×270. The grid has
 * to stay on-screen and non-overlapping at both ends, which is tedious to check by eye in-game
 * and trivial to check here.</p>
 */
final class BuilderGridLayoutTest {

    private static final int TOP = 46;
    private static final int BOTTOM_INSET = 44; // Back button + margin

    @Test
    @DisplayName("Tiles never overlap each other, at any viewport size")
    void tilesDoNotOverlap() {
        for (int[] size : new int[][] {{1920, 1080}, {960, 540}, {640, 360}, {480, 270}, {320, 240}}) {
            BuilderGridLayout layout = layoutFor(size[0], size[1]);
            // Column 0 must end before column 1 starts; row 0 before row 1.
            assertTrue(layout.xFor(0) + layout.tileWidth() <= layout.xFor(1),
                    "columns overlap at " + size[0] + "x" + size[1]);
            assertTrue(layout.yFor(0) + layout.tileHeight() <= layout.yFor(2),
                    "rows overlap at " + size[0] + "x" + size[1]);
        }
    }

    @Test
    @DisplayName("The grid stays horizontally on-screen and centred")
    void gridIsCentredAndOnScreen() {
        for (int[] size : new int[][] {{1920, 1080}, {854, 480}, {480, 270}}) {
            BuilderGridLayout layout = layoutFor(size[0], size[1]);
            int right = layout.xFor(1) + layout.tileWidth();
            assertTrue(layout.originX() >= 0, "grid runs off the left at " + size[0]);
            assertTrue(right <= size[0], "grid runs off the right at " + size[0]);
            // Centred: equal slack on both sides, give or take integer rounding.
            int leftSlack = layout.originX();
            int rightSlack = size[0] - right;
            assertTrue(Math.abs(leftSlack - rightSlack) <= 1,
                    "grid is not centred at " + size[0] + " (left=" + leftSlack + ", right=" + rightSlack + ")");
        }
    }

    @Test
    @DisplayName("Tiles keep a 16:9 aspect so screenshot art isn't squashed")
    void tilesKeep16By9() {
        for (int[] size : new int[][] {{1920, 1080}, {640, 360}, {480, 270}}) {
            BuilderGridLayout layout = layoutFor(size[0], size[1]);
            double aspect = (double) layout.tileWidth() / layout.tileHeight();
            assertTrue(Math.abs(aspect - 16.0 / 9.0) < 0.15,
                    "aspect " + aspect + " at " + size[0] + "x" + size[1]);
        }
    }

    @Test
    @DisplayName("A wide viewport is capped rather than stretching tiles into billboards")
    void wideViewportIsCapped() {
        BuilderGridLayout wide = layoutFor(1920, 1080);
        BuilderGridLayout wider = layoutFor(3840, 1080);
        assertEquals(wide.tileWidth(), wider.tileWidth(),
                "tile width should hit the cap and stop growing");
    }

    private static BuilderGridLayout layoutFor(int width, int height) {
        return BuilderGridLayout.of(width, height, TOP, height - BOTTOM_INSET);
    }
}
