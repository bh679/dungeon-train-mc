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
        DetailPages p = DetailPages.of(0, 12);
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
        DetailPages p = DetailPages.of(17, 12);
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
        DetailPages p = DetailPages.of(3, 12);
        assertEquals(2, p.pageCount());
        assertEquals(3, p.end(1));
    }

    @Test
    @DisplayName("a body too short for a row and the pager shows the model page only")
    void noRoom() {
        DetailPages p = DetailPages.of(6, 1);
        assertFalse(p.paged());
        assertEquals(1, p.pageCount());
        assertEquals(1, DetailPages.of(6, 0).pageCount());
    }

    @Test
    @DisplayName("Loot pages come last, after the rows")
    void lootPages() {
        DetailPages p = DetailPages.of(17, 12, 2);
        assertTrue(p.hasPager());
        assertEquals(5, p.pageCount(), "model, two row pages, two loot");
        assertEquals(0, p.first(1));
        assertEquals(11, p.end(1));
        assertEquals(11, p.first(2));
        assertEquals(17, p.end(2));
        assertEquals(3, p.firstLootPage());
        assertFalse(p.isLootPage(2));
        assertTrue(p.isLootPage(3));
        assertTrue(p.isLootPage(4));
        assertEquals(1, p.lootIndex(4));
        assertEquals(0, p.end(3), "no rows on a Loot page");

        DetailPages lootOnly = DetailPages.of(0, 12, 1);
        assertTrue(lootOnly.hasPager(), "loot alone is worth a pager");
        assertEquals(2, lootOnly.pageCount());
        assertEquals(1, lootOnly.firstLootPage());
        assertFalse(DetailPages.of(17, 12).isLootPage(1), "no loot, no Loot page");
    }

    @Test
    void submittedAnswersPageComesLastAfterLoot() {
        // 17 rows at 11 per page = 2 row pages, then 2 Loot pages, then the answers.
        DetailPages p = DetailPages.of(17, 12, 2, 1);
        assertEquals(6, p.pageCount());
        assertEquals(5, p.firstSubmitPage());
        assertTrue(p.isLootPage(3) && p.isLootPage(4));
        assertFalse(p.isLootPage(5), "the answers page is not a Loot page");
        assertTrue(p.isSubmitPage(5));
        assertFalse(p.isSubmitPage(4));
        assertFalse(p.isRowPage(5));
        assertTrue(p.hasPager());
    }

    @Test
    void blocksPagesSitBetweenRowsAndLoot() {
        // 17 rows at 11 per page = 2 row pages, 2 Blocks pages, 1 Loot page, the answers.
        DetailPages p = DetailPages.of(17, 12, 1, 1).withBlockPages(2);
        assertEquals(7, p.pageCount());
        assertEquals(3, p.firstBlockPage());
        assertTrue(p.isRowPage(2));
        assertFalse(p.isRowPage(3), "a Blocks page is not a row page");
        assertTrue(p.isBlockPage(3) && p.isBlockPage(4));
        assertEquals(1, p.blockIndex(4));
        assertEquals(5, p.firstLootPage());
        assertTrue(p.isLootPage(5));
        assertFalse(p.isBlockPage(5));
        assertTrue(p.isSubmitPage(6));
    }

    @Test
    void blocksAloneMakeAPager() {
        DetailPages p = DetailPages.of(0, 12).withBlockPages(1);
        assertEquals(2, p.pageCount());
        assertTrue(p.isBlockPage(1));
        assertFalse(p.isBlockPage(0), "the model page is never a Blocks page");
        assertTrue(p.hasPager());
        assertFalse(DetailPages.of(0, 12).isBlockPage(1), "no blocks, no Blocks page");
    }

    @Test
    void answersAloneStillMakeAPager() {
        DetailPages p = DetailPages.of(0, 12, 0, 1);
        assertEquals(2, p.pageCount());
        assertTrue(p.isSubmitPage(1));
        assertFalse(p.isLootPage(1));
        assertTrue(p.hasPager());
    }

    @Test
    void noAnswersNoPage() {
        DetailPages p = DetailPages.of(0, 12, 1, 0);
        assertEquals(2, p.pageCount());
        assertTrue(p.isLootPage(1));
        assertFalse(p.isSubmitPage(1));
        // At most one answers page, whatever is asked for.
        assertEquals(3, DetailPages.of(0, 12, 1, 5).pageCount());
    }
}
