package games.brennan.dungeontrain.client.menu.editorscreen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the detail pane cuts its settings rows into pages. */
class EditorDetailPanePagesTest {

    @Test
    @DisplayName("rows that fit are one page with no pager")
    void fitsOnOnePage() {
        EditorDetailPane.Pages p = EditorDetailPane.Pages.of(5, 8);
        assertFalse(p.paged());
        assertFalse(p.hasPager());
        assertEquals(1, p.pageCount());
        assertEquals(0, p.first(0));
        assertEquals(5, p.end(0));
        assertEquals(0, p.clamp(3));
    }

    @Test
    @DisplayName("an overflowing list gives the last slot to the pager and pages the rest")
    void overflowPages() {
        // 17 rows in 8 slots: 7 per page -> 3 pages (7, 7, 3).
        EditorDetailPane.Pages p = EditorDetailPane.Pages.of(17, 8);
        assertTrue(p.paged());
        assertTrue(p.hasPager());
        assertEquals(7, p.perPage());
        assertEquals(3, p.pageCount());
        assertEquals(7, p.first(1));
        assertEquals(14, p.end(1));
        assertEquals(14, p.first(2));
        assertEquals(17, p.end(2));
        assertEquals(2, p.clamp(9));
        assertEquals(0, p.clamp(-1));
    }

    @Test
    @DisplayName("exactly filling the slots is still one page — the pager only appears when it saves a row")
    void exactFit() {
        EditorDetailPane.Pages p = EditorDetailPane.Pages.of(8, 8);
        assertFalse(p.paged());
        assertEquals(1, p.pageCount());
        // One more row and the pager costs a slot: 9 rows -> 7 + 2.
        assertEquals(2, EditorDetailPane.Pages.of(9, 8).pageCount());
    }

    @Test
    @DisplayName("a pane with no room at all is harmless")
    void noSlots() {
        EditorDetailPane.Pages p = EditorDetailPane.Pages.of(6, 0);
        assertFalse(p.paged());
        assertEquals(1, p.pageCount());
        assertEquals(0, p.end(0));
        // A single slot pages one row at a time, with no pager to spend the slot on.
        EditorDetailPane.Pages one = EditorDetailPane.Pages.of(3, 1);
        assertEquals(3, one.pageCount());
        assertFalse(one.hasPager());
    }
}
