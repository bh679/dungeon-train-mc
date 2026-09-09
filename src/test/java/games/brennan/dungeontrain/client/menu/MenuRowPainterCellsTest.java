package games.brennan.dungeontrain.client.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The N-cell row: how it decomposes, where its cells are, and which of them answer a click. */
final class MenuRowPainterCellsTest {

    private static CommandMenuEntry six() {
        return new CommandMenuEntry.Cells(List.of(
            new CommandMenuEntry.ClientAction("name", () -> { }, false),
            new CommandMenuEntry.Stay("−", "dec"),
            new CommandMenuEntry.TypeArg("20", "0-100", "prefix", "", ""),
            new CommandMenuEntry.Stay("+", "inc"),
            new CommandMenuEntry.Label(""),
            new CommandMenuEntry.Label("")),
            List.of(0.46, 0.53, 0.63, 0.70, 0.86));
    }

    @Test
    @DisplayName("six cells, five dividers, and the first cell's label names the row")
    void decomposes() {
        CommandMenuEntry row = six();
        assertEquals(6, MenuRowPainter.cellsOf(row).length);
        assertArrayEquals(new double[] {0.46, 0.53, 0.63, 0.70, 0.86}, MenuRowPainter.cellBoundaries(row));
        assertEquals("name", row.label());
    }

    @Test
    @DisplayName("hit-testing lands on the cell under the point and refuses the blanks")
    void hits() {
        CommandMenuEntry row = six();
        assertEquals(0, MenuRowPainter.hitCell(row, 10, 0, 100));
        assertEquals(1, MenuRowPainter.hitCell(row, 48, 0, 100));
        assertEquals(2, MenuRowPainter.hitCell(row, 58, 0, 100));
        assertEquals(3, MenuRowPainter.hitCell(row, 65, 0, 100));
        assertEquals(-1, MenuRowPainter.hitCell(row, 75, 0, 100), "a blank label is not clickable");
        assertEquals(-1, MenuRowPainter.hitCell(row, 95, 0, 100));
        assertEquals(-1, MenuRowPainter.hitCell(row, 100, 0, 100), "past the right edge");
    }

    @Test
    @DisplayName("a cell's span uses the same rounding the painter draws with, and clamps the index")
    void spans() {
        CommandMenuEntry row = six();
        assertArrayEquals(new int[] {0, 46}, MenuRowPainter.cellSpan(row, 0, 0, 100));
        assertArrayEquals(new int[] {53, 63}, MenuRowPainter.cellSpan(row, 2, 0, 100));
        assertArrayEquals(new int[] {86, 100}, MenuRowPainter.cellSpan(row, 5, 0, 100));
        assertArrayEquals(new int[] {86, 100}, MenuRowPainter.cellSpan(row, 9, 0, 100));
        assertArrayEquals(new int[] {10, 210}, MenuRowPainter.cellSpan(new CommandMenuEntry.Label("x"), 0, 10, 210));
    }

    @Test
    @DisplayName("a Cells row insists on one divider fewer than cells")
    void shape() {
        assertThrows(IllegalArgumentException.class, () -> new CommandMenuEntry.Cells(
            List.of(new CommandMenuEntry.Label("a"), new CommandMenuEntry.Label("b")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CommandMenuEntry.Cells(List.of(), List.of()));
    }
}
