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
            List<Rect> regions = List.of(l.filter(), l.categoryStrip(), l.typeStrip(), l.grid(), l.header(), l.preview(),
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
    @DisplayName("the category strip sits between the filter row and the type strip, full width")
    void categoryStripBetweenFilterAndTypes() {
        for (int[] s : SIZES) {
            InventoryEditorLayout l = InventoryEditorLayout.of(s[0], s[1]);
            assertEquals(l.filter().bottom() + 2, l.categoryStrip().y());
            assertEquals(l.categoryStrip().bottom() + 2, l.typeStrip().y());
            assertEquals(l.grid().x(), l.categoryStrip().x());
            assertEquals(l.grid().w(), l.categoryStrip().w());
            assertEquals(InventoryEditorLayout.STRIP_H, l.categoryStrip().h());
        }
    }

    @Test
    @DisplayName("the search row spans both columns, and both columns start beneath it")
    void filterRowSpansBothColumns() {
        for (int[] s : SIZES) {
            for (boolean expanded : new boolean[] {true, false}) {
                InventoryEditorLayout l = InventoryEditorLayout.of(s[0], s[1], expanded);
                Rect inner = l.panel().inset(InventoryEditorLayout.PAD);
                assertEquals(inner.x(), l.filter().x());
                assertEquals(inner.w(), l.filter().w());
                assertEquals(l.filter().bottom() + 2, l.header().y(), "right pane starts under the row");
                assertTrue(l.grid().y() >= l.filter().bottom() + 2);
                assertEquals(l.filter().x(), l.grid().x());
                assertTrue(l.grid().right() < l.header().x(), "the grid stays left of the right pane");
            }
        }
    }

    @Test
    @DisplayName("collapsed, both strips vanish and the grid takes their two rows back")
    void collapsedFilters() {
        for (int[] s : SIZES) {
            InventoryEditorLayout open = InventoryEditorLayout.of(s[0], s[1], true);
            InventoryEditorLayout shut = InventoryEditorLayout.of(s[0], s[1], false);
            assertEquals(0, shut.categoryStrip().h());
            assertEquals(0, shut.typeStrip().h());
            assertEquals(shut.filter().bottom() + 2, shut.grid().y());
            assertEquals(open.grid().h() + 2 * (InventoryEditorLayout.STRIP_H + 2), shut.grid().h());
            assertEquals(open.grid().bottom(), shut.grid().bottom());
            List<Rect> regions = List.of(shut.filter(), shut.grid(), shut.header(), shut.preview(),
                shut.sheet(), shut.icons(), shut.settings(), shut.test());
            for (Rect r : regions) assertTrue(inside(r, shut.panel()), s[0] + "x" + s[1] + " " + r);
            for (int i = 0; i < regions.size(); i++) {
                for (int j = i + 1; j < regions.size(); j++) {
                    assertFalse(overlaps(regions.get(i), regions.get(j)));
                }
            }
        }
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
