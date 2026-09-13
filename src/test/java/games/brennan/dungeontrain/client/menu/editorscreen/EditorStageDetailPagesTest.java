package games.brennan.dungeontrain.client.menu.editorscreen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stage preview's page split: block grid pages first, template rows after. */
final class EditorStageDetailPagesTest {

    @Test
    @DisplayName("blocks on one page and no templates still makes two pages: the grid, then an empty template page")
    void minimal() {
        EditorStageDetailPane.Pages p = new EditorStageDetailPane.Pages(new IconGridPages(5, 4, 3), 0, 6);
        assertEquals(2, p.pageCount());
        assertTrue(p.hasPager());
        assertTrue(p.isBlockPage(0));
        assertFalse(p.isBlockPage(1));
        assertEquals(0, p.firstRow(1));
        assertEquals(0, p.endRow(1));
    }

    @Test
    @DisplayName("template rows page after the last block page and are cut at the row count")
    void split() {
        EditorStageDetailPane.Pages p = new EditorStageDetailPane.Pages(new IconGridPages(30, 4, 3), 14, 6);
        assertEquals(3, p.blockPages());
        assertEquals(3, p.templatePages());
        assertEquals(6, p.pageCount());
        assertTrue(p.isBlockPage(2));
        assertFalse(p.isBlockPage(3));
        assertEquals(0, p.firstRow(3));
        assertEquals(6, p.endRow(3));
        assertEquals(12, p.firstRow(5));
        assertEquals(14, p.endRow(5));
        assertEquals(5, p.clamp(99));
    }
}
