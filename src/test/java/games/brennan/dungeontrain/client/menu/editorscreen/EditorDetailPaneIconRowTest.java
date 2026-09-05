package games.brennan.dungeontrain.client.menu.editorscreen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The toolbar has to hold all eight buttons at every pane width the mod supports — it ran off the
 * right edge at the narrowest one, which is the size a 720p window at GUI scale 3 actually gets.
 */
final class EditorDetailPaneIconRowTest {

    private static final int COUNT = 8;

    /** The right edge of the last button, relative to the row's left edge. */
    private static int spanOf(EditorDetailPane.IconRow row, int x0) {
        return row.x()[row.x().length - 1] + row.cell() - x0;
    }

    @Test
    @DisplayName("every button fits inside the row at each pane width the layout produces")
    void fitsAtEveryPaneWidth() {
        for (int[] size : new int[][] {{427, 240}, {480, 270}, {640, 360}, {854, 480}, {1920, 1080}}) {
            InventoryEditorLayout l = InventoryEditorLayout.of(size[0], size[1]);
            int width = l.icons().w();
            EditorDetailPane.IconRow row = EditorDetailPane.layoutIcons(COUNT, l.icons().x(), width);
            assertEquals(COUNT, row.x().length);
            assertTrue(spanOf(row, l.icons().x()) <= width,
                size[0] + "x" + size[1] + ": row spans " + spanOf(row, l.icons().x()) + " of " + width);
            assertTrue(row.cell() >= EditorDetailPane.MIN_ICON_CELL,
                size[0] + "x" + size[1] + ": buttons shrank to " + row.cell());
        }
    }

    @Test
    @DisplayName("buttons never overlap, and stay in order")
    void buttonsDoNotOverlap() {
        EditorDetailPane.IconRow row = EditorDetailPane.layoutIcons(COUNT, 10, 163);
        for (int i = 1; i < row.x().length; i++) {
            assertTrue(row.x()[i - 1] + row.cell() <= row.x()[i],
                "button " + (i - 1) + " runs into " + i);
        }
    }

    @Test
    @DisplayName("a wide pane draws them at full size with the grouping intact")
    void wideKeepsTheGrouping() {
        int width = 260;
        EditorDetailPane.IconRow row = EditorDetailPane.layoutIcons(COUNT, 0, width);
        assertEquals(EditorDetailPane.ICON_CELL, row.cell());
        // The three group breaks are wider than the ordinary gap between buttons.
        int ordinary = row.x()[1] - row.x()[0];
        assertTrue(row.x()[3] - row.x()[2] > ordinary, "risk grouping should still show");
        assertTrue(spanOf(row, 0) <= width);
    }

    @Test
    @DisplayName("a narrow pane shrinks the buttons but keeps Remove and Clear held apart")
    void narrowKeepsTheGroupingAndShrinks() {
        int width = COUNT * EditorDetailPane.ICON_CELL + (COUNT - 1) * EditorDetailPane.ICON_GAP;
        EditorDetailPane.IconRow row = EditorDetailPane.layoutIcons(COUNT, 0, width);
        assertTrue(row.cell() < EditorDetailPane.ICON_CELL, "the buttons give way first");
        assertTrue(row.cell() >= EditorDetailPane.MIN_ICON_CELL);
        assertTrue(row.x()[3] - row.x()[2] > row.x()[1] - row.x()[0],
            "the grouping is a safety cue, so it outlives a couple of pixels of button");
        assertTrue(spanOf(row, 0) <= width);
    }

    @Test
    @DisplayName("only once the buttons would stop reading as buttons does the grouping go")
    void veryNarrowDropsTheGrouping() {
        // Too tight for legible buttons with the breaks in place.
        int width = COUNT * EditorDetailPane.MIN_ICON_CELL + (COUNT - 1) * EditorDetailPane.ICON_GAP;
        EditorDetailPane.IconRow row = EditorDetailPane.layoutIcons(COUNT, 0, width);
        assertEquals(row.x()[1] - row.x()[0], row.x()[3] - row.x()[2], "the breaks are gone");
        assertTrue(row.cell() >= EditorDetailPane.MIN_ICON_CELL);
        assertTrue(spanOf(row, 0) <= width);
    }

    @Test
    @DisplayName("no buttons is not a crash")
    void empty() {
        EditorDetailPane.IconRow row = EditorDetailPane.layoutIcons(0, 0, 100);
        assertEquals(0, row.x().length);
    }
}
