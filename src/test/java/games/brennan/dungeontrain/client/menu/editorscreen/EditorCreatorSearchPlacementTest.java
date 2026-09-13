package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.editorscreen.InventoryEditorLayout.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The creator search panel sits over the browser column and never on the right pane's preview. */
final class EditorCreatorSearchPlacementTest {

    private static final int[][] SIZES = {{427, 240}, {480, 270}, {640, 360}, {854, 480}, {1920, 1080}};

    private static boolean overlaps(Rect a, Rect b) {
        return a.x() < b.right() && b.x() < a.right() && a.y() < b.bottom() && b.y() < a.bottom();
    }

    @Test
    @DisplayName("at every size, expanded or collapsed, the panel stays inside the browser column")
    void staysOverTheBrowser() {
        for (int[] s : SIZES) {
            for (boolean expanded : new boolean[] {true, false}) {
                InventoryEditorLayout l = InventoryEditorLayout.of(s[0], s[1], expanded);
                Rect p = EditorCreatorSearch.place(l);
                String at = s[0] + "x" + s[1] + (expanded ? " expanded " : " collapsed ") + p;
                assertTrue(p.w() > 0 && p.h() > 0, at);
                assertTrue(p.x() >= l.filter().x() && p.right() <= l.grid().right(), at);
                assertTrue(p.y() >= l.filter().y() && p.bottom() <= l.grid().bottom(), at);
                assertFalse(overlaps(p, l.preview()), at + " on the preview");
                assertFalse(overlaps(p, l.header()), at + " on the header");
                assertFalse(overlaps(p, l.sheet()), at + " on the sheet");
            }
        }
    }
}
