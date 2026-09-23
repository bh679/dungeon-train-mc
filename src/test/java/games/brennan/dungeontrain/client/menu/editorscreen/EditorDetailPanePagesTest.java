package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.MenuTestLanguage;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the detail pane cuts its body into pages: the model and its sheet first, then the rows. */
@ExtendWith(MenuTestLanguage.class)
class EditorDetailPanePagesTest {

    @Test
    @DisplayName("with no rows there is the model page alone and no pager")
    void modelOnly() {
        EditorDetailPane.Pages p = EditorDetailPane.Pages.of(0, 12);
        assertFalse(p.paged());
        assertFalse(p.hasPager());
        assertEquals(1, p.pageCount());
        assertEquals(0, p.end(0));
        assertEquals(0, p.clamp(3));
    }

    @Test
    @DisplayName("rows start on page two and fill the body less the pager's slot")
    void rowsAfterTheModel() {
        // 17 rows in a 12-slot body: 11 per row page -> model + 2 row pages (11, 6).
        EditorDetailPane.Pages p = EditorDetailPane.Pages.of(17, 12);
        assertTrue(p.paged());
        assertTrue(p.hasPager());
        assertEquals(11, p.perPage());
        assertEquals(2, p.rowPages());
        assertEquals(3, p.pageCount());
        assertEquals(0, p.end(0));          // the model page has no rows
        assertEquals(0, p.first(1));
        assertEquals(11, p.end(1));
        assertEquals(11, p.first(2));
        assertEquals(17, p.end(2));
        assertEquals(2, p.clamp(9));
        assertEquals(0, p.clamp(-1));
    }

    @Test
    @DisplayName("a few rows still get a page of their own rather than squeezing under the sheet")
    void fewRowsStillPage() {
        EditorDetailPane.Pages p = EditorDetailPane.Pages.of(3, 12);
        assertEquals(2, p.pageCount());
        assertEquals(3, p.end(1));
    }

    @Test
    @DisplayName("a body too short for a row and the pager shows the model page only")
    void noRoom() {
        EditorDetailPane.Pages p = EditorDetailPane.Pages.of(6, 1);
        assertFalse(p.paged());
        assertEquals(1, p.pageCount());
        assertEquals(1, EditorDetailPane.Pages.of(6, 0).pageCount());
    }

    @Test
    @DisplayName("Loot pages sit between the model and the rows, and shift the rows back")
    void lootPages() {
        EditorDetailPane.Pages p = EditorDetailPane.Pages.of(17, 12, 2);
        assertTrue(p.hasPager());
        assertEquals(5, p.pageCount(), "model, two loot, two row pages");
        assertTrue(p.isLootPage(EditorDetailPane.Pages.LOOT_PAGE));
        assertTrue(p.isLootPage(2));
        assertEquals(1, p.lootIndex(2));
        assertFalse(p.isLootPage(3));
        assertEquals(0, p.end(1), "no rows on a Loot page");
        assertEquals(0, p.first(3));
        assertEquals(11, p.end(3));
        assertEquals(11, p.first(4));
        assertEquals(17, p.end(4));

        EditorDetailPane.Pages lootOnly = EditorDetailPane.Pages.of(0, 12, 1);
        assertTrue(lootOnly.hasPager(), "loot alone is worth a pager");
        assertEquals(2, lootOnly.pageCount());
        assertFalse(EditorDetailPane.Pages.of(17, 12).isLootPage(1), "no loot, no Loot page");
    }
}
