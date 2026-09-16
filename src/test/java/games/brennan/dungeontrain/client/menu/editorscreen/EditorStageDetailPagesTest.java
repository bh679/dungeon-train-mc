package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.MenuTestLanguage;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stage preview's pages: its sections laid end to end — overview, palette, stone, blocks, templates. */
@ExtendWith(MenuTestLanguage.class)
final class EditorStageDetailPagesTest {

    private static EditorStageDetailPane.Section s(EditorStageDetailPane.SectionKind kind, int rows, int perPage) {
        return new EditorStageDetailPane.Section(kind, rows, perPage);
    }

    @Test
    @DisplayName("every section has at least one page, even empty, so the pager can say so")
    void emptySectionsStillPage() {
        EditorStageDetailPane.Pages p = new EditorStageDetailPane.Pages(List.of(
            s(EditorStageDetailPane.SectionKind.OVERVIEW, 1, 1),
            s(EditorStageDetailPane.SectionKind.PALETTE, 0, 5),
            s(EditorStageDetailPane.SectionKind.STONE, 0, 5),
            s(EditorStageDetailPane.SectionKind.BLOCKS, 0, 3),
            s(EditorStageDetailPane.SectionKind.TEMPLATES, 0, 6)));
        assertEquals(5, p.pageCount());
        assertTrue(p.hasPager());
        assertEquals(EditorStageDetailPane.SectionKind.OVERVIEW, p.kindOf(0));
        assertEquals(EditorStageDetailPane.SectionKind.PALETTE, p.kindOf(1));
        assertEquals(EditorStageDetailPane.SectionKind.STONE, p.kindOf(2));
        assertEquals(EditorStageDetailPane.SectionKind.BLOCKS, p.kindOf(3));
        assertEquals(EditorStageDetailPane.SectionKind.TEMPLATES, p.kindOf(4));
        assertEquals(0, p.firstRow(4));
        assertEquals(0, p.endRow(4));
    }

    @Test
    @DisplayName("rows page within their section and are cut at the section's row count")
    void split() {
        EditorStageDetailPane.Pages p = new EditorStageDetailPane.Pages(List.of(
            s(EditorStageDetailPane.SectionKind.OVERVIEW, 1, 1),
            s(EditorStageDetailPane.SectionKind.PALETTE, 7, 5),
            s(EditorStageDetailPane.SectionKind.STONE, 8, 5),
            s(EditorStageDetailPane.SectionKind.BLOCKS, 5, 3),
            s(EditorStageDetailPane.SectionKind.TEMPLATES, 14, 6)));
        // 1 + 2 + 2 + 2 + 3
        assertEquals(10, p.pageCount());
        assertEquals(EditorStageDetailPane.SectionKind.PALETTE, p.kindOf(2));
        assertEquals(5, p.firstRow(2));
        assertEquals(7, p.endRow(2));
        assertEquals(EditorStageDetailPane.SectionKind.STONE, p.kindOf(3));
        assertEquals(0, p.firstRow(3));
        assertEquals(5, p.endRow(3));
        assertEquals(EditorStageDetailPane.SectionKind.BLOCKS, p.kindOf(6));
        assertEquals(3, p.firstRow(6));
        assertEquals(5, p.endRow(6));
        assertEquals(EditorStageDetailPane.SectionKind.TEMPLATES, p.kindOf(9));
        assertEquals(12, p.firstRow(9));
        assertEquals(14, p.endRow(9));
        assertEquals(9, p.clamp(99));
        assertEquals(3, p.firstPageOf(EditorStageDetailPane.SectionKind.STONE));
        assertFalse(p.kindOf(1) == EditorStageDetailPane.SectionKind.OVERVIEW);
    }
}
