package games.brennan.dungeontrain.client.menu.editorscreen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TemplateTileGridLayoutTest {

    private static final TemplateTileGridLayout GRID = TemplateTileGridLayout.of(10, 20, 300, 100, 52, 3);

    @Test
    @DisplayName("columns come from the width; a click on a tile's corner round-trips to its index")
    void indexRoundTrip() {
        assertEquals(5, GRID.columns());
        // Two rows fit the 100px viewport unscrolled; the third only once scrolled.
        for (int i = 0; i < 10; i++) {
            assertEquals(i, GRID.indexAt(GRID.xFor(i) + 1, GRID.yFor(i, 0) + 1, 0, 12), "tile " + i);
            assertEquals(i, GRID.indexAt(GRID.xFor(i) + 51, GRID.yFor(i, 30) + 51, 30, 12), "scrolled tile " + i);
        }
        assertEquals(TemplateTileGridLayout.NONE, GRID.indexAt(GRID.xFor(10) + 1, GRID.yFor(10, 0) + 1, 0, 12));
        int max = GRID.maxScroll(12);
        assertEquals(10, GRID.indexAt(GRID.xFor(10) + 1, GRID.yFor(10, max) + 1, max, 12));
    }

    @Test
    @DisplayName("gaps, empty slots and points outside the viewport miss")
    void misses() {
        assertEquals(TemplateTileGridLayout.NONE, GRID.indexAt(GRID.xFor(1) - 1, GRID.yFor(0, 0), 0, 12));
        assertEquals(TemplateTileGridLayout.NONE, GRID.indexAt(GRID.xFor(4), GRID.yFor(0, 0), 0, 3));
        assertEquals(TemplateTileGridLayout.NONE, GRID.indexAt(5, 25, 0, 12));
        assertEquals(TemplateTileGridLayout.NONE, GRID.indexAt(15, 500, 0, 12));
        assertEquals(TemplateTileGridLayout.NONE, GRID.indexAt(15, 20 + 52 + 1, 0, 12));
    }

    @Test
    @DisplayName("content height counts rows without a trailing gap, and scroll clamps to it")
    void heightsAndScroll() {
        assertEquals(0, GRID.contentHeight(0));
        assertEquals(52, GRID.contentHeight(5));
        assertEquals(52 * 2 + 3, GRID.contentHeight(6));
        assertEquals(0, GRID.maxScroll(5));
        assertEquals(52 * 3 + 6 - 100, GRID.maxScroll(11));
        assertEquals(GRID.maxScroll(11), GRID.clampScroll(999, 11));
        assertEquals(0, GRID.clampScroll(-5, 11));
    }

    /**
     * The sub-variant grid's shape: content laid out below the panel it scrolls inside — 200px down
     * from a viewport that starts at 20 and is 100 tall, which is roughly where a full main grid
     * leaves it.
     */
    private static final TemplateTileGridLayout BELOW =
        TemplateTileGridLayout.of(10, 220, 300, 100, 52, 3).withViewport(20, 100);

    @Test
    @DisplayName("a grid starting below its viewport is seen and clicked through the viewport")
    void contentBelowTheViewport() {
        // Unscrolled it rests off the bottom, as the sub grid does before the main one is scrolled.
        assertFalse(BELOW.isVisible(0, 0));

        // Scrolled far enough to sit against the top of the panel: on screen, and clickable there.
        // This is the case that was broken — the old window started at the content's own y, so a
        // tile that had risen past 220 was called invisible while it was still inside the panel.
        int scroll = 220 - 30;
        int top = BELOW.yFor(0, scroll);
        assertEquals(30, top, "the row now rests inside the panel");
        assertTrue(BELOW.isVisible(0, scroll));
        assertEquals(0, BELOW.indexAt(BELOW.xFor(0) + 1, top + 1, scroll, 6));

        // Off the top of the panel is still off: the window moved, it did not disappear.
        int past = 220;
        assertFalse(BELOW.isVisible(0, past + 52));
        assertEquals(TemplateTileGridLayout.NONE,
            BELOW.indexAt(BELOW.xFor(0) + 1, BELOW.yFor(0, past + 52) + 1, past + 52, 6));

        // Positions are untouched by the viewport — only what counts as seen.
        assertEquals(TemplateTileGridLayout.of(10, 220, 300, 100, 52, 3).yFor(3, scroll),
            BELOW.yFor(3, scroll));
    }

    @Test
    @DisplayName("visibility follows the scroll")
    void visibility() {
        assertTrue(GRID.isVisible(0, 0));
        assertFalse(GRID.isVisible(10, 0));
        assertTrue(GRID.isVisible(10, GRID.maxScroll(11)));
        assertFalse(GRID.isVisible(0, GRID.maxScroll(11)));
    }
}
