package games.brennan.dungeontrain.client.menu.plot;

import games.brennan.dungeontrain.client.menu.plot.EditorHelpPanelRenderer.CellKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Hit-testing for the editor Welcome panel's clickable cells.
 *
 * <p>The close (X) button shares the header row with the panel title, so it is the one cell whose
 * identity depends on {@code hitX} as well as which row the ray landed in. These tests pin that the
 * close box is confined to the header row's right-hand square — a title click must not dismiss the
 * panel, and the wiki row must keep working at every x including under the X.</p>
 */
class EditorHelpPanelHitTest {

    /** Panel-local y inside the header band (row 0). */
    private static final double HEADER_Y = EditorHelpPanelRenderer.halfHeight()
        - EditorHelpPanelRenderer.ROW_H / 2.0;

    /** Panel-local y inside the wiki-button band (the last row). */
    private static final double WIKI_Y = EditorHelpPanelRenderer.halfHeight()
        - (EditorHelpPanelRenderer.ROW_WIKI_BUTTON + 0.5) * EditorHelpPanelRenderer.ROW_H;

    private static CellKind cellAt(double x, double y) {
        return EditorHelpPanelRenderer.hitFor(x, y).cell();
    }

    @Test
    @DisplayName("header right-hand square is the close button")
    void closeBox() {
        double insideCloseX = EditorHelpPanelRenderer.HALF_W - EditorHelpPanelRenderer.CLOSE_W / 2.0;
        assertEquals(CellKind.CLOSE_BUTTON, cellAt(insideCloseX, HEADER_Y));
        // The very corner of the panel still counts — the box runs to the edge.
        assertEquals(CellKind.CLOSE_BUTTON,
            cellAt(EditorHelpPanelRenderer.HALF_W - 0.001, HEADER_Y));
    }

    @Test
    @DisplayName("the rest of the header row is not clickable")
    void headerElsewhereIsInert() {
        assertEquals(CellKind.NONE, cellAt(0.0, HEADER_Y), "clicking the title must not close");
        assertEquals(CellKind.NONE, cellAt(-EditorHelpPanelRenderer.HALF_W + 0.01, HEADER_Y));
        // Just left of the close box.
        assertEquals(CellKind.NONE,
            cellAt(EditorHelpPanelRenderer.HALF_W - EditorHelpPanelRenderer.CLOSE_W - 0.01, HEADER_Y));
    }

    @Test
    @DisplayName("the wiki button is unchanged, at every x")
    void wikiRowUnchanged() {
        assertEquals(CellKind.WIKI_BUTTON, cellAt(0.0, WIKI_Y));
        // The close box lives on row 0 only, so the wiki row stays clickable under it.
        assertEquals(CellKind.WIKI_BUTTON,
            cellAt(EditorHelpPanelRenderer.HALF_W - 0.001, WIKI_Y));
    }

    @Test
    @DisplayName("body rows and off-panel hits resolve to nothing")
    void inertHits() {
        double bodyY = EditorHelpPanelRenderer.halfHeight() - 1.5 * EditorHelpPanelRenderer.ROW_H;
        assertEquals(CellKind.NONE, cellAt(0.0, bodyY), "welcome text row");
        assertEquals(CellKind.NONE, cellAt(EditorHelpPanelRenderer.HALF_W + 0.5, HEADER_Y),
            "right of the panel");
        assertEquals(CellKind.NONE, cellAt(0.0, EditorHelpPanelRenderer.halfHeight() + 0.5),
            "above the panel");
    }
}
