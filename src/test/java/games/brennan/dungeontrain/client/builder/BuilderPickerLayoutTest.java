package games.brennan.dungeontrain.client.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry guard for the picker across the GUI scales players actually use.
 *
 * <p>At scale 1 a 1080p window is ~1920×1080 GUI pixels; at scale 4 it is ~480×270. The tiles,
 * the detail column and the Go button have to stay on-screen and non-overlapping at both ends,
 * which is tedious to check by eye in-game and trivial to check here.</p>
 */
final class BuilderPickerLayoutTest {

    private static final int TOP = 46;
    private static final int BOTTOM_INSET = 36; // Back button + margins

    private static final int[][] SIZES = {{1920, 1080}, {960, 540}, {854, 480}, {640, 360}, {480, 270}, {320, 240}};

    @Test
    @DisplayName("Tiles never overlap each other, at any viewport size")
    void tilesDoNotOverlap() {
        for (int[] size : SIZES) {
            List<BuilderPickerLayout.Rect> tiles = layoutFor(size[0], size[1]).tiles();
            assertEquals(4, tiles.size());
            assertTrue(tiles.get(0).right() <= tiles.get(1).x(), "columns overlap at " + label(size));
            assertTrue(tiles.get(0).bottom() <= tiles.get(2).y(), "rows overlap at " + label(size));
            assertEquals(tiles.get(0).w(), tiles.get(3).w(), "tiles differ in size at " + label(size));
        }
    }

    @Test
    @DisplayName("Tiles keep a 16:9 aspect so screenshot art isn't squashed")
    void tilesKeep16By9() {
        for (int[] size : new int[][] {{1920, 1080}, {640, 360}, {480, 270}}) {
            BuilderPickerLayout.Rect tile = layoutFor(size[0], size[1]).tiles().get(0);
            double aspect = (double) tile.w() / tile.h();
            assertTrue(Math.abs(aspect - 16.0 / 9.0) < 0.15, "aspect " + aspect + " at " + label(size));
        }
    }

    @Test
    @DisplayName("The detail column sits right of the tiles and stacks without overlap")
    void detailColumnStacks() {
        for (int[] size : SIZES) {
            BuilderPickerLayout layout = layoutFor(size[0], size[1]);
            int tilesRight = layout.tiles().get(1).right();
            assertTrue(tilesRight <= layout.header().x(), "tiles run into the detail column at " + label(size));
            assertTrue(layout.header().bottom() <= layout.preview().y(), "header overlaps preview at " + label(size));
            assertTrue(layout.preview().bottom() <= layout.description().y(), "preview overlaps text at " + label(size));
            assertTrue(layout.description().bottom() <= layout.go().y(), "text overlaps Go at " + label(size));
            assertTrue(layout.preview().h() > 0, "preview collapsed at " + label(size));
            assertEquals(layout.header().x(), layout.go().x(), "column is not aligned at " + label(size));
        }
    }

    @Test
    @DisplayName("The body stays on-screen, above the Back button, and centred")
    void bodyIsOnScreenAndCentred() {
        for (int[] size : new int[][] {{1920, 1080}, {854, 480}, {480, 270}}) {
            BuilderPickerLayout layout = layoutFor(size[0], size[1]);
            int left = Math.min(layout.tiles().get(0).x(), layout.header().x());
            int right = layout.go().right();
            assertTrue(left >= 0, "body runs off the left at " + label(size));
            assertTrue(right <= size[0], "body runs off the right at " + label(size));
            assertTrue(layout.go().bottom() <= size[1] - BOTTOM_INSET, "Go runs into Back at " + label(size));
            assertTrue(layout.tiles().get(0).y() >= TOP, "tiles run into the title at " + label(size));
            int leftSlack = left;
            int rightSlack = size[0] - right;
            assertTrue(Math.abs(leftSlack - rightSlack) <= 1,
                    "body is not centred at " + label(size) + " (left=" + leftSlack + ", right=" + rightSlack + ")");
        }
    }

    @Test
    @DisplayName("A wide viewport is capped rather than stretching tiles into billboards")
    void wideViewportIsCapped() {
        BuilderPickerLayout wide = layoutFor(1920, 1080);
        BuilderPickerLayout wider = layoutFor(3840, 1080);
        assertEquals(wide.tiles().get(0).w(), wider.tiles().get(0).w(), "tile width should hit the cap and stop growing");
        assertEquals(wide.go().w(), wider.go().w(), "detail width should hit the cap and stop growing");
    }

    private static BuilderPickerLayout layoutFor(int width, int height) {
        return BuilderPickerLayout.of(width, height, TOP, height - BOTTOM_INSET);
    }

    private static String label(int[] size) {
        return size[0] + "x" + size[1];
    }
}
