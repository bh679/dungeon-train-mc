package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer.CellKind;
import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer.RowKind;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The panel's row walk.
 *
 * <p>Counting, hit-testing and drawing all consume {@code EditorPlotLabelsRenderer.rows}. Before
 * that they each re-derived the sequence with their own cursor, and a row inserted at a different
 * position in any one of them silently made clicks land on the wrong cell. These tests pin the
 * order and pin that a hit inside row <i>N</i> resolves to the cell row <i>N</i> actually is.</p>
 */
class EditorPlotLabelsRendererTest {

    private static final BlockPos POS = new BlockPos(0, 250, 0);

    private static EditorPlotLabelsPacket.Entry entry(String category, boolean inPlot,
                                                      int weight, int length, int width, int height) {
        return new EditorPlotLabelsPacket.Entry(
            POS, "default", weight, category, "portal_room", "default",
            inPlot, false, false, length, width, height);
    }

    private static EditorPlotLabelsPacket.Entry portalInPlot() {
        return entry("PORTALS", true, 1, 11, 13, 7);
    }

    /** Centre of row {@code index}, counting from the top. */
    private static double rowCentreY(EditorPlotLabelsPacket.Entry e, int index) {
        double halfH = EditorPlotLabelsRenderer.halfHeight(e);
        return halfH - (index + 0.5) * EditorPlotLabelsRenderer.ROW_H;
    }

    @Test
    @DisplayName("A portal room in-plot shows name, weight, L/W/H, Enter and the action row, in that order")
    void portalInPlot_rowOrder() {
        assertArrayEquals(
            new RowKind[]{RowKind.NAME, RowKind.WEIGHT, RowKind.LENGTH, RowKind.WIDTH,
                RowKind.HEIGHT, RowKind.ENTER, RowKind.ACTION},
            EditorPlotLabelsRenderer.rows(portalInPlot()));
        assertEquals(7, EditorPlotLabelsRenderer.rowCount(portalInPlot()));
    }

    @Test
    @DisplayName("Out of the plot a portal room is just name + weight — no steppers to reach anyway")
    void portalOutOfPlot_hasNoDimensionRows() {
        EditorPlotLabelsPacket.Entry e = entry("PORTALS", false, 1, 11, 13, 7);
        assertArrayEquals(new RowKind[]{RowKind.NAME, RowKind.WEIGHT},
            EditorPlotLabelsRenderer.rows(e));
    }

    @Test
    @DisplayName("Other categories are untouched: a carriage keeps name, weight, Enter, action, Contents")
    void carriage_rowsUnchanged() {
        EditorPlotLabelsPacket.Entry e = new EditorPlotLabelsPacket.Entry(
            POS, "standard", 1, "CARRIAGES", "standard", "standard", true, false, false);
        assertArrayEquals(
            new RowKind[]{RowKind.NAME, RowKind.WEIGHT, RowKind.ENTER, RowKind.ACTION, RowKind.CONTENTS},
            EditorPlotLabelsRenderer.rows(e));
        // A carriage reports no authored size, so it can never grow dimension rows.
        assertEquals(EditorPlotLabelsPacket.NO_SIZE, e.roomLength());
    }

    @Test
    @DisplayName("A PORTALS entry with no reported size gets no steppers — nothing to step")
    void portalWithoutSize_hasNoDimensionRows() {
        EditorPlotLabelsPacket.Entry e = new EditorPlotLabelsPacket.Entry(
            POS, "default", 1, "PORTALS", "portal_room", "default", true, false, false);
        assertArrayEquals(new RowKind[]{RowKind.NAME, RowKind.WEIGHT, RowKind.ENTER, RowKind.ACTION},
            EditorPlotLabelsRenderer.rows(e));
    }

    @Test
    @DisplayName("Each stepper row's left third decrements ITS OWN axis and the right third increments it")
    void dimensionRows_hitTheirOwnAxis() {
        EditorPlotLabelsPacket.Entry e = portalInPlot();
        // halfWidth needs a Font; MIN_HALF_W is the floor and is what a short name resolves to,
        // so the thirds split is computed against it directly.
        double halfW = EditorPlotLabelsRenderer.MIN_HALF_W;
        double left = -halfW + 0.05;
        double right = halfW - 0.05;

        RowKind[] rows = EditorPlotLabelsRenderer.rows(e);
        CellKind[][] expected = {
            {CellKind.LENGTH_DEC, CellKind.LENGTH_INC},
            {CellKind.WIDTH_DEC, CellKind.WIDTH_INC},
            {CellKind.HEIGHT_DEC, CellKind.HEIGHT_INC},
        };
        RowKind[] axes = {RowKind.LENGTH, RowKind.WIDTH, RowKind.HEIGHT};

        for (int a = 0; a < axes.length; a++) {
            int rowIdx = indexOf(rows, axes[a]);
            double y = rowCentreY(e, rowIdx);
            assertEquals(expected[a][0], EditorPlotLabelsRenderer.cellAt(e, halfW, left, y),
                axes[a] + " left third must decrement " + axes[a]);
            assertEquals(expected[a][1], EditorPlotLabelsRenderer.cellAt(e, halfW, right, y),
                axes[a] + " right third must increment " + axes[a]);
            // The number between the arrows opens the type-all-three field.
            assertEquals(CellKind.SIZE_TYPE, EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0, y),
                axes[a] + " middle cell should open the size field");
        }
    }

    @Test
    @DisplayName("The rows around the steppers still resolve to themselves — no off-by-one drift")
    void neighbouringRows_stillResolveCorrectly() {
        EditorPlotLabelsPacket.Entry e = portalInPlot();
        RowKind[] rows = EditorPlotLabelsRenderer.rows(e);
        double halfW = EditorPlotLabelsRenderer.MIN_HALF_W;

        assertEquals(CellKind.NAME,
            EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0, rowCentreY(e, indexOf(rows, RowKind.NAME))));
        assertEquals(CellKind.WEIGHT_DEC,
            EditorPlotLabelsRenderer.cellAt(e, halfW, -halfW + 0.05, rowCentreY(e, indexOf(rows, RowKind.WEIGHT))));
        assertEquals(CellKind.BUTTON_ENTER_INSIDE,
            EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0, rowCentreY(e, indexOf(rows, RowKind.ENTER))));
        assertEquals(CellKind.ACTION_SAVE,
            EditorPlotLabelsRenderer.cellAt(e, halfW, -halfW + 0.05, rowCentreY(e, indexOf(rows, RowKind.ACTION))));
    }

    @Test
    @DisplayName("The weight row's number stays dead — only dimension rows open the size field")
    void weightNumberIsNotATypingTarget() {
        EditorPlotLabelsPacket.Entry e = portalInPlot();
        double halfW = EditorPlotLabelsRenderer.MIN_HALF_W;
        RowKind[] rows = EditorPlotLabelsRenderer.rows(e);
        assertEquals(CellKind.NONE,
            EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0, rowCentreY(e, indexOf(rows, RowKind.WEIGHT))));
    }

    @Test
    @DisplayName("A hit outside the panel resolves to nothing")
    void outsidePanel_isNone() {
        EditorPlotLabelsPacket.Entry e = portalInPlot();
        double halfW = EditorPlotLabelsRenderer.MIN_HALF_W;
        double halfH = EditorPlotLabelsRenderer.halfHeight(e);
        assertEquals(CellKind.NONE, EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0, halfH + 0.1));
        assertEquals(CellKind.NONE, EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0, -halfH - 0.1));
        assertEquals(CellKind.NONE,
            EditorPlotLabelsRenderer.cellAt(e, halfW, EditorPlotLabelsRenderer.MIN_HALF_W + 1.0, 0.0));
    }

    @Test
    @DisplayName("Dimension rows report their own axis, and the panel grows with them")
    void dimensionValues_mapToTheirAxis() {
        EditorPlotLabelsPacket.Entry e = entry("PORTALS", true, 1, 21, 17, 9);
        assertEquals(21, EditorPlotLabelsRenderer.dimensionValue(e, RowKind.LENGTH));
        assertEquals(17, EditorPlotLabelsRenderer.dimensionValue(e, RowKind.WIDTH));
        assertEquals(9, EditorPlotLabelsRenderer.dimensionValue(e, RowKind.HEIGHT));

        EditorPlotLabelsPacket.Entry outOfPlot = entry("PORTALS", false, 1, 21, 17, 9);
        assertNotEquals(EditorPlotLabelsRenderer.halfHeight(e),
            EditorPlotLabelsRenderer.halfHeight(outOfPlot));
    }

    private static int indexOf(RowKind[] rows, RowKind kind) {
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] == kind) return i;
        }
        throw new AssertionError("row " + kind + " not present");
    }
}
