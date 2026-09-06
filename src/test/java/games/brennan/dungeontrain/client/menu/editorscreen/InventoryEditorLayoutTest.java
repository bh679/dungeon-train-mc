package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.editorscreen.InventoryEditorLayout.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The screen must fit every logical size a player can have, down to GUI scale 3 at 720p. */
final class InventoryEditorLayoutTest {

    private static final int[][] SIZES = {{427, 240}, {480, 270}, {640, 360}, {854, 480}, {1920, 1080}};

    private static boolean overlaps(Rect a, Rect b) {
        return a.x() < b.right() && b.x() < a.right() && a.y() < b.bottom() && b.y() < a.bottom();
    }

    private static boolean inside(Rect inner, Rect outer) {
        return inner.x() >= outer.x() && inner.y() >= outer.y()
            && inner.right() <= outer.right() && inner.bottom() <= outer.bottom();
    }

    @Test
    @DisplayName("no region overlaps another and every region sits inside the panel")
    void regionsNest() {
        for (int[] s : SIZES) {
            InventoryEditorLayout l = InventoryEditorLayout.of(s[0], s[1]);
            List<Rect> regions = List.of(l.filter(), l.typeStrip(), l.grid(), l.header(), l.preview(),
                l.sheet(), l.icons(), l.settings(), l.test());
            for (Rect r : regions) assertTrue(inside(r, l.panel()), s[0] + "x" + s[1] + " " + r);
            for (int i = 0; i < regions.size(); i++) {
                for (int j = i + 1; j < regions.size(); j++) {
                    assertFalse(overlaps(regions.get(i), regions.get(j)),
                        s[0] + "x" + s[1] + ": " + regions.get(i) + " overlaps " + regions.get(j));
                }
            }
        }
    }

    @Test
    @DisplayName("nothing is drawn over the hotbar")
    void hotbarStaysClear() {
        for (int[] s : SIZES) {
            InventoryEditorLayout l = InventoryEditorLayout.of(s[0], s[1]);
            assertTrue(l.panel().bottom() <= s[1] - InventoryEditorLayout.HOTBAR_RESERVE);
            assertTrue(l.test().bottom() <= l.panel().bottom());
        }
    }

    @Test
    @DisplayName("the preview gives way first and never exceeds its cap")
    void previewBounds() {
        InventoryEditorLayout small = InventoryEditorLayout.of(427, 240);
        InventoryEditorLayout large = InventoryEditorLayout.of(1920, 1080);
        assertTrue(small.preview().h() >= InventoryEditorLayout.PREVIEW_MIN_H);
        assertTrue(small.preview().h() < large.preview().h());
        assertTrue(large.preview().w() <= InventoryEditorLayout.PREVIEW_MAX_W);
        assertTrue(large.preview().h() <= InventoryEditorLayout.PREVIEW_MAX_H);
        assertEquals(InventoryEditorLayout.TILE_SMALL, small.tile());
        assertEquals(InventoryEditorLayout.TILE_LARGE, large.tile());
    }

    @Test
    @DisplayName("the grid fits at least five tiles across and two rows down at the floor size")
    void gridColumns() {
        InventoryEditorLayout l = InventoryEditorLayout.of(427, 240);
        TemplateTileGridLayout g = TemplateTileGridLayout.of(l.grid().x(), l.grid().y(), l.grid().w(), l.grid().h(), l.tile(), 3);
        assertTrue(g.columns() >= 5, "columns " + g.columns());
        assertTrue(l.grid().h() >= l.tile() * 2 + 3, "two rows should fit: " + l.grid().h());
    }

    @Test
    @DisplayName("a degenerate viewport does not throw")
    void degenerate() {
        InventoryEditorLayout l = InventoryEditorLayout.of(0, 0);
        assertEquals(0, l.panel().w());
        assertTrue(l.grid().h() >= 0);
    }
}
