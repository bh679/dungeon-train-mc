package games.brennan.dungeontrain.client.menu.editorscreen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Stages tab's icon-grid paging arithmetic. */
final class IconGridPagesTest {

    @Test
    @DisplayName("an empty grid is one page with no pager")
    void empty() {
        IconGridPages p = new IconGridPages(0, 4, 3);
        assertEquals(1, p.pageCount());
        assertFalse(p.hasPager());
        assertEquals(0, p.first(0));
        assertEquals(0, p.end(0));
    }

    @Test
    @DisplayName("a grid that fits on one page has no pager; one more icon opens a second page")
    void exactFitThenOverflow() {
        assertFalse(new IconGridPages(12, 4, 3).hasPager());
        IconGridPages p = new IconGridPages(13, 4, 3);
        assertTrue(p.hasPager());
        assertEquals(2, p.pageCount());
        assertEquals(12, p.first(1));
        assertEquals(13, p.end(1));
    }

    @Test
    @DisplayName("pages are clamped to the ones that exist and a page ends at the item count")
    void clampAndEnd() {
        IconGridPages p = new IconGridPages(30, 5, 2);
        assertEquals(3, p.pageCount());
        assertEquals(2, p.clamp(9));
        assertEquals(0, p.clamp(-1));
        assertEquals(20, p.first(2));
        assertEquals(30, p.end(2));
        assertEquals(10, p.end(0));
    }

    @Test
    @DisplayName("a degenerate rect still yields one cell so the page never divides by zero")
    void degenerateRect() {
        IconGridPages p = new IconGridPages(3, 0, 0);
        assertEquals(1, p.perPage());
        assertEquals(3, p.pageCount());
    }
}
