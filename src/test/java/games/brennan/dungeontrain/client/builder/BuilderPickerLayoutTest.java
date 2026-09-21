package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderMode;
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
    private static final int BOTTOM_INSET = 12; // bottom margin; Back sits inside the body now

    private static final int[][] SIZES = {{1920, 1080}, {960, 540}, {854, 480}, {640, 360}, {480, 270}, {320, 240}};

    @Test
    @DisplayName("Tiles never overlap each other, at any viewport size")
    void tilesDoNotOverlap() {
        for (int[] size : SIZES) {
            List<BuilderPickerLayout.Rect> tiles = layoutFor(size[0], size[1]).tiles();
            assertEquals(BuilderPickerLayout.COLUMNS * BuilderPickerLayout.ROWS, tiles.size());
            assertTrue(tiles.size() >= BuilderMode.values().length, "every mode gets a tile at " + label(size));
            assertEquals(tiles.get(0).x(), tiles.get(1).x(), "one column at " + label(size));
            assertTrue(tiles.get(0).bottom() <= tiles.get(BuilderPickerLayout.COLUMNS).y(), "rows overlap at " + label(size));
            assertEquals(tiles.get(0).w(), tiles.get(3).w(), "tiles differ in size at " + label(size));
        }
    }

    @Test
    @DisplayName("Tiles span the column and are never taller than 16:9 — the art is cover-cropped, not squashed")
    void tilesSpanTheColumn() {
        for (int[] size : new int[][] {{1920, 1080}, {640, 360}, {480, 270}}) {
            BuilderPickerLayout layout = layoutFor(size[0], size[1]);
            BuilderPickerLayout.Rect tile = layout.tiles().get(0);
            assertEquals(layout.back().w(), tile.w(), "tile as wide as its column at " + label(size));
            assertTrue(tile.h() <= tile.w() * 9 / 16, "taller than 16:9 at " + label(size));
        }
    }

    @Test
    @DisplayName("The detail column sits right of the tiles and stacks without overlap")
    void detailColumnStacks() {
        for (int[] size : SIZES) {
            BuilderPickerLayout layout = layoutFor(size[0], size[1]);
            int tilesRight = layout.tiles().get(1).right();
            assertTrue(tilesRight <= layout.header().x(), "tiles run into the detail column at " + label(size));
            assertTrue(layout.tiles().get(3).bottom() <= layout.back().y(), "tiles run into Back at " + label(size));
            assertEquals(layout.go().y(), layout.back().y(), "Back and Go are not on one row at " + label(size));
            assertTrue(layout.back().right() <= layout.go().x(), "Back overlaps Go at " + label(size));
            assertTrue(layout.header().bottom() <= layout.preview().y(), "header overlaps preview at " + label(size));
            assertTrue(layout.preview().bottom() <= layout.description().y(), "preview overlaps text at " + label(size));
            assertTrue(layout.description().bottom() <= layout.go().y(), "text overlaps Go at " + label(size));
            assertTrue(layout.preview().h() > 0, "preview collapsed at " + label(size));
            assertEquals(layout.header().x(), layout.go().x(), "column is not aligned at " + label(size));
        }
    }

    @Test
    @DisplayName("The body stays on-screen and centred")
    void bodyIsOnScreenAndCentred() {
        for (int[] size : new int[][] {{1920, 1080}, {854, 480}, {480, 270}}) {
            BuilderPickerLayout layout = layoutFor(size[0], size[1]);
            int left = Math.min(layout.back().x(), layout.tiles().get(0).x());
            int right = layout.go().right();
            assertTrue(left >= 0, "body runs off the left at " + label(size));
            assertTrue(right <= size[0], "body runs off the right at " + label(size));
            assertTrue(layout.go().bottom() <= size[1] - BOTTOM_INSET, "Go runs off the bottom at " + label(size));
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
