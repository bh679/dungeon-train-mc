package games.brennan.dungeontrain.client.menu.editorscreen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Stage and Band rows step through Auto at both ends, and the toolbar row fits the pane. */
final class TestModeControlsTest {

    private static final List<String> VALUES = List.of("a", "b", "c");

    @Test
    void cyclesForwardThroughAuto() {
        assertEquals("a", TestModeControls.cycle(VALUES, "", +1));
        assertEquals("b", TestModeControls.cycle(VALUES, "a", +1));
        assertEquals("", TestModeControls.cycle(VALUES, "c", +1));
    }

    @Test
    void cyclesBackThroughAuto() {
        assertEquals("c", TestModeControls.cycle(VALUES, "", -1));
        assertEquals("", TestModeControls.cycle(VALUES, "a", -1));
        assertEquals("a", TestModeControls.cycle(VALUES, "b", -1));
    }

    @Test
    void unknownValueStepsFromAuto() {
        assertEquals("a", TestModeControls.cycle(VALUES, "gone", +1));
        assertEquals("", TestModeControls.cycle(List.of(), "a", +1));
    }

    @Test
    void testRowSitsUnderTheEditingRowAndAboveTheModel() {
        for (int[] s : new int[][] {{427, 240}, {640, 360}, {1920, 1080}}) {
            InventoryEditorLayout base = InventoryEditorLayout.of(s[0], s[1]);
            InventoryEditorLayout l = base.withTestRow();
            assertEquals(0, base.testIcons().h(), "no test row outside a test");
            assertTrue(l.testIcons().y() >= l.icons().bottom(), "below the editing row");
            assertTrue(l.preview().y() >= l.testIcons().bottom(), "above the model");
            assertEquals(base.preview().bottom(), l.preview().bottom(), "the model gives the row its room");
            int[] xs = EditorDetailPane.layoutTestIcons(4, l.testIcons().x());
            assertTrue(xs[3] + EditorDetailPane.ICON_CELL <= l.testIcons().right(), "four buttons fit at " + s[0]);
        }
    }
}
