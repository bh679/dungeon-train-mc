package games.brennan.dungeontrain.client.menu.editorscreen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one paging model behind both detail panes: sections laid end to end, empty ones kept or dropped. */
final class SectionPagesTest {

    enum K { A, B, C }

    @Test
    @DisplayName("a section shown always keeps a page when empty; one shown when-present disappears")
    void emptySections() {
        SectionPages<K> p = new SectionPages<>(List.of(
            SectionPages.Section.always(K.A, 1, 1),
            SectionPages.Section.when(K.B, 0, 5),
            SectionPages.Section.always(K.C, 0, 5)));
        assertEquals(2, p.pageCount());
        assertEquals(K.A, p.kindOf(0));
        assertEquals(K.C, p.kindOf(1));
        assertEquals(0, p.pagesOf(K.B));
        assertFalse(p.isOf(1, K.B));
        assertTrue(p.isOf(1, K.C));
        assertEquals(0, p.endRow(1));
    }

    @Test
    @DisplayName("rows page within their section, cut at the section's row count, and clamp holds")
    void split() {
        SectionPages<K> p = new SectionPages<>(List.of(
            SectionPages.Section.always(K.A, 1, 1),
            SectionPages.Section.when(K.B, 7, 5),
            SectionPages.Section.when(K.C, 3, 1)));
        assertEquals(1 + 2 + 3, p.pageCount());
        assertTrue(p.hasPager());
        assertEquals(5, p.firstRow(2));
        assertEquals(7, p.endRow(2));
        assertEquals(3, p.firstPageOf(K.C));
        assertEquals(2, p.indexWithin(5));
        assertEquals(5, p.clamp(99));
        assertEquals(0, p.clamp(-3));
    }

    @Test
    @DisplayName("the template pane's pages are the same sections: model, rows, blocks, loot, submitted")
    void detailPagesAreSections() {
        DetailPages d = DetailPages.of(9, 5, 2, 1).withBlockPages(1);
        SectionPages<DetailPages.Kind> s = d.sections();
        // 1 model + 3 row pages (9 rows, 4 per page) + 1 blocks + 2 loot + 1 submitted
        assertEquals(8, s.pageCount());
        assertEquals(d.pageCount(), s.pageCount());
        assertEquals(d.firstBlockPage(), s.firstPageOf(DetailPages.Kind.BLOCKS));
        assertEquals(d.firstLootPage(), s.firstPageOf(DetailPages.Kind.LOOT));
        assertEquals(d.firstSubmitPage(), s.firstPageOf(DetailPages.Kind.SUBMIT));
        assertTrue(d.isRowPage(3));
        assertEquals(8, d.first(3));
        assertEquals(9, d.end(3));
        assertTrue(d.isBlockPage(4));
        assertTrue(d.isLootPage(6));
        assertEquals(1, d.lootIndex(6));
        assertTrue(d.isSubmitPage(7));
    }

    @Test
    @DisplayName("a layout with no row slots gives the rows no page at all")
    void noSlotsNoRowPage() {
        DetailPages d = DetailPages.of(12, 1);
        assertEquals(0, d.rowPages());
        assertEquals(1, d.pageCount());
        assertFalse(d.paged());
        assertFalse(d.isRowPage(1));
    }
}
