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

    @Test
    @DisplayName("visibility follows the scroll")
    void visibility() {
        assertTrue(GRID.isVisible(0, 0));
        assertFalse(GRID.isVisible(10, 0));
        assertTrue(GRID.isVisible(10, GRID.maxScroll(11)));
        assertFalse(GRID.isVisible(0, GRID.maxScroll(11)));
    }
}
