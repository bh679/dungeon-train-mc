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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        return entry(category, inPlot, weight, length, width, height, "bedrock_lock");
    }

    private static EditorPlotLabelsPacket.Entry entry(String category, boolean inPlot,
                                                      int weight, int length, int width, int height,
                                                      String mode) {
        return new EditorPlotLabelsPacket.Entry(
            POS, "default", weight, category, "portal_room", "default",
            inPlot, false, false, length, width, height, mode);
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
    @DisplayName("A portal room in-plot shows name, weight, L/W/H, Walls, Enter and the action row")
    void portalInPlot_rowOrder() {
        assertArrayEquals(
            new RowKind[]{RowKind.NAME, RowKind.WEIGHT, RowKind.LENGTH, RowKind.WIDTH,
                RowKind.HEIGHT, RowKind.MODE, RowKind.ENTER, RowKind.ACTION},
            EditorPlotLabelsRenderer.rows(portalInPlot()));
        assertEquals(8, EditorPlotLabelsRenderer.rowCount(portalInPlot()));
    }

    @Test
    @DisplayName("The Walls row is one button, and the rows around it do not shift under it")
    void modeRow_isOneButtonAndDoesNotDisplaceItsNeighbours() {
        EditorPlotLabelsPacket.Entry e = portalInPlot();
        RowKind[] rows = EditorPlotLabelsRenderer.rows(e);
        double halfW = EditorPlotLabelsRenderer.MIN_HALF_W;
        double y = rowCentreY(e, indexOf(rows, RowKind.MODE));

        // The whole row cycles, wherever on it the click lands.
        for (double x : new double[]{-halfW + 0.05, 0.0, halfW - 0.05}) {
            assertEquals(CellKind.MODE_CYCLE, EditorPlotLabelsRenderer.cellAt(e, halfW, x, y));
        }
        // And the rows either side of the insertion still resolve to themselves.
        assertEquals(CellKind.HEIGHT_TYPE, EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0,
            rowCentreY(e, indexOf(rows, RowKind.HEIGHT))));
        assertEquals(CellKind.BUTTON_ENTER_INSIDE, EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0,
            rowCentreY(e, indexOf(rows, RowKind.ENTER))));
    }

    @Test
    @DisplayName("An entry carrying no mode gets no Walls row — every category but portals")
    void noMode_meansNoModeRow() {
        EditorPlotLabelsPacket.Entry e =
            entry("PORTALS", true, 1, 11, 13, 7, EditorPlotLabelsPacket.NO_MODE);
        assertArrayEquals(
            new RowKind[]{RowKind.NAME, RowKind.WEIGHT, RowKind.LENGTH, RowKind.WIDTH,
                RowKind.HEIGHT, RowKind.ENTER, RowKind.ACTION},
            EditorPlotLabelsRenderer.rows(e));
    }

    @Test
    @DisplayName("Out of the plot there is no Walls row either — the same gate the steppers use")
    void modeRow_needsThePlayerInThePlot() {
        EditorPlotLabelsPacket.Entry e = entry("PORTALS", false, 1, 11, 13, 7, "endless_open");
        assertArrayEquals(new RowKind[]{RowKind.NAME, RowKind.WEIGHT},
            EditorPlotLabelsRenderer.rows(e));
    }

    @Test
    @DisplayName("The Walls label names the mode, and an unreadable tag shows the default it behaves as")
    void modeLabel_readsTheMode() {
        assertEquals("Walls: Endless Open", EditorPlotLabelsRenderer.modeLabel("endless_open"));
        assertEquals("Walls: Endless Repetition",
            EditorPlotLabelsRenderer.modeLabel("endless_repetition"));
        // parse is total, so a tag hand-edited into weights.json shows what the room will do rather
        // than the misspelling.
        assertEquals("Walls: Bedrock Lock", EditorPlotLabelsRenderer.modeLabel("endles_open"));
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
            {CellKind.LENGTH_DEC, CellKind.LENGTH_INC, CellKind.LENGTH_TYPE},
            {CellKind.WIDTH_DEC, CellKind.WIDTH_INC, CellKind.WIDTH_TYPE},
            {CellKind.HEIGHT_DEC, CellKind.HEIGHT_INC, CellKind.HEIGHT_TYPE},
        };
        RowKind[] axes = {RowKind.LENGTH, RowKind.WIDTH, RowKind.HEIGHT};

        for (int a = 0; a < axes.length; a++) {
            int rowIdx = indexOf(rows, axes[a]);
            double y = rowCentreY(e, rowIdx);
            assertEquals(expected[a][0], EditorPlotLabelsRenderer.cellAt(e, halfW, left, y),
                axes[a] + " left third must decrement " + axes[a]);
            assertEquals(expected[a][1], EditorPlotLabelsRenderer.cellAt(e, halfW, right, y),
                axes[a] + " right third must increment " + axes[a]);
            // Every cell on the row belongs to that row's axis and no other — the number in the
            // middle types the same axis its arrows step.
            assertEquals(expected[a][2], EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0, y),
                axes[a] + " middle cell must type " + axes[a] + " alone");
        }
    }

    @Test
    @DisplayName("Each dimension row reports its own command token")
    void dimensionRows_carryTheirAxisToken() {
        assertEquals("length", EditorPlotLabelsRenderer.dimensionAxis(RowKind.LENGTH));
        assertEquals("width", EditorPlotLabelsRenderer.dimensionAxis(RowKind.WIDTH));
        assertEquals("height", EditorPlotLabelsRenderer.dimensionAxis(RowKind.HEIGHT));
        assertEquals("", EditorPlotLabelsRenderer.dimensionAxis(RowKind.WEIGHT));
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
    @DisplayName("The weight row's number stays dead — only dimension rows type")
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

    // ---- panel width ----

    /** Stand-in for a Font: every glyph six pixels wide, which is close enough to vanilla's. */
    private static final java.util.function.ToIntFunction<String> SIX_PX = s -> s.length() * 6;

    @Test
    @DisplayName("The panel widens to fit the Walls label — a short name must not clip a long mode")
    void panelFitsTheWallsLabel() {
        // "default" is seven characters; "Walls: Endless Repetition" is twenty-five. Sizing off the
        // name alone is what had the label spilling out past both edges of the backdrop.
        EditorPlotLabelsPacket.Entry e = entry("PORTALS", true, 1, 11, 13, 7, "endless_repetition");
        double halfW = EditorPlotLabelsRenderer.halfWidth(e, SIX_PX);
        double labelHalfW =
            SIX_PX.applyAsInt(EditorPlotLabelsRenderer.modeLabel(e.roomMode())) * 0.025 / 2.0;
        assertTrue(halfW >= labelHalfW, "panel half-width " + halfW + " clips a " + labelHalfW + " label");
    }

    @Test
    @DisplayName("A panel with no Walls row keeps the width it always had")
    void panelWithoutModeRowIsUnchanged() {
        EditorPlotLabelsPacket.Entry withMode = entry("PORTALS", true, 1, 11, 13, 7, "endless_repetition");
        EditorPlotLabelsPacket.Entry without =
            entry("PORTALS", true, 1, 11, 13, 7, EditorPlotLabelsPacket.NO_MODE);
        assertEquals(EditorPlotLabelsRenderer.MIN_HALF_W,
            EditorPlotLabelsRenderer.halfWidth(without, SIX_PX));
        assertTrue(EditorPlotLabelsRenderer.halfWidth(withMode, SIX_PX)
            > EditorPlotLabelsRenderer.halfWidth(without, SIX_PX));
    }

    @Test
    @DisplayName("A name longer than the Walls label still wins — the widest row sets the width")
    void longNameStillWidensThePanel() {
        EditorPlotLabelsPacket.Entry longName = new EditorPlotLabelsPacket.Entry(
            POS, "a_very_long_portal_room_variant_name_indeed", 1, "PORTALS",
            "portal_room", "default", true, false, false, 11, 13, 7, "bedrock_lock");
        double halfW = EditorPlotLabelsRenderer.halfWidth(longName, SIX_PX);
        assertTrue(halfW >= SIX_PX.applyAsInt(longName.name()) * 0.025 / 2.0);
    }

    private static int indexOf(RowKind[] rows, RowKind kind) {
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] == kind) return i;
        }
        throw new AssertionError("row " + kind + " not present");
    }
}
