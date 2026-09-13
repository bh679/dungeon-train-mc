package games.brennan.dungeontrain.client.menu.editorscreen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stage preview's page split: the overview first, block grid pages next, template rows after. */
final class EditorStageDetailPagesTest {

    @Test
    @DisplayName("the overview, blocks on one page and no templates makes three pages: overview, grid, an empty template page")
    void minimal() {
        EditorStageDetailPane.Pages p = new EditorStageDetailPane.Pages(new IconGridPages(5, 4, 3), 0, 6);
        assertEquals(3, p.pageCount());
        assertTrue(p.hasPager());
        assertTrue(p.isOverview(0));
        assertFalse(p.isBlockPage(0));
        assertTrue(p.isBlockPage(1));
        assertEquals(0, p.blockPage(1));
        assertFalse(p.isBlockPage(2));
        assertEquals(0, p.firstRow(2));
        assertEquals(0, p.endRow(2));
    }

    @Test
    @DisplayName("template rows page after the last block page and are cut at the row count")
    void split() {
        EditorStageDetailPane.Pages p = new EditorStageDetailPane.Pages(new IconGridPages(30, 4, 3), 14, 6);
        assertEquals(3, p.blockPages());
        assertEquals(3, p.templatePages());
        assertEquals(7, p.pageCount());
        assertTrue(p.isBlockPage(3));
        assertEquals(2, p.blockPage(3));
        assertFalse(p.isBlockPage(4));
        assertEquals(0, p.firstRow(4));
        assertEquals(6, p.endRow(4));
        assertEquals(12, p.firstRow(6));
        assertEquals(14, p.endRow(6));
        assertEquals(6, p.clamp(99));
        assertFalse(p.isOverview(1));
    }
}
