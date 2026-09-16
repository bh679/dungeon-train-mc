package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.editorscreen.InventoryEditorLayout.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Help tab's fit, at the sizes that matter, without a client. */
final class EditorHelpPaneTest {

    private static final int[][] SIZES = { {427, 240}, {640, 360}, {854, 480} };

    @Test
    @DisplayName("every topic row fits the left column without scrolling, down to the floor size")
    void rowsFit() {
        for (int[] size : SIZES) {
            InventoryEditorLayout layout = InventoryEditorLayout.of(size[0], size[1], false);
            Rect rows = EditorHelpPane.rect(layout);
            assertTrue(EditorHelpPane.rowsFit(rows), size[0] + "x" + size[1] + ": " + rows.h() / EditorHelpPane.ROW_H
                + " rows for " + EditorHelpTopic.values().length + " topics");
        }
    }

    @Test
    @DisplayName("the body runs from the top of the preview slot to the foot of the test slot")
    void bodySpansTheRightColumn() {
        for (int[] size : SIZES) {
            InventoryEditorLayout layout = InventoryEditorLayout.of(size[0], size[1], false);
            Rect body = EditorHelpPane.body(layout);
            assertEquals(layout.preview().y(), body.y());
            assertEquals(layout.test().bottom(), body.bottom());
            assertEquals(layout.preview().x(), body.x());
            assertEquals(layout.preview().w(), body.w());
            // Nothing pokes into the left column.
            assertTrue(body.x() >= EditorHelpPane.rect(layout).right());
        }
    }

    @Test
    @DisplayName("the pager takes the last row of the body and the text keeps the rest")
    void pagerAtTheFoot() {
        Rect body = new Rect(200, 40, 220, 150);
        Rect text = EditorHelpPane.bodyAbovePager(body);
        Rect pager = EditorHelpPane.pager(body);
        assertEquals(body.y(), text.y());
        assertEquals(pager.y(), text.bottom());
        assertEquals(body.bottom(), pager.bottom());
        assertEquals(InventoryEditorLayout.PAGER_H, pager.h());
    }

    @Test
    @DisplayName("a page holds ten lines or more at the floor size, and page counts round up")
    void pageArithmetic() {
        InventoryEditorLayout floor = InventoryEditorLayout.of(427, 240, false);
        int perPage = EditorHelpPane.linesPerPage(EditorHelpPane.bodyAbovePager(EditorHelpPane.body(floor)).h());
        assertTrue(perPage >= 10, "only " + perPage + " lines per page at the floor size");
        assertEquals(1, EditorHelpPane.pageCount(0, perPage));
        assertEquals(1, EditorHelpPane.pageCount(perPage, perPage));
        assertEquals(2, EditorHelpPane.pageCount(perPage + 1, perPage));
        assertEquals(3, EditorHelpPane.pageCount(perPage * 3, perPage));
    }
}
