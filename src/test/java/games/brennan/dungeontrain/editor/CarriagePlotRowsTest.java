package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.editor.CarriagePlotRows.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CarriagePlotRows}: one row per pool, each laid end to end at its own plots' lengths. */
final class CarriagePlotRowsTest {

    private static final int G = EditorLayout.GAP;

    @Test
    @DisplayName("each pool's plots form their own row, counted from the start, at their own lengths")
    void ownRows() {
        Map<String, Row> pool = Map.of(
            "standard", Row.ROOMS, "cargo", Row.GROUPS, "fancy", Row.ROOMS,
            "twin", Row.HALVES, "flatbedgroup", Row.GROUPS, "halfy", Row.HALVES);
        Map<String, Integer> len = Map.of(
            "standard", 9, "cargo", 27, "fancy", 9, "twin", 13, "flatbedgroup", 27, "halfy", 13);
        CarriagePlotRows.Rows rows = CarriagePlotRows.rows(
            new String[] {"standard", "cargo", "fancy", "twin", "flatbedgroup", "halfy"},
            pool::get, len::get, 0);

        assertEquals(0, rows.startX().get("standard"));
        assertEquals(9 + G, rows.startX().get("fancy"), "a Room follows the Room before it, not the Group");
        assertEquals(0, rows.startX().get("cargo"));
        assertEquals(27 + G, rows.startX().get("flatbedgroup"));
        assertEquals(0, rows.startX().get("twin"));
        assertEquals(13 + G, rows.startX().get("halfy"));
        assertEquals(2 * (9 + G), rows.endOf(Row.ROOMS));
        assertEquals(2 * (27 + G), rows.endOf(Row.GROUPS));
        assertEquals(Row.HALVES, rows.rowOf().get("halfy"));
    }

    @Test
    @DisplayName("Rooms keep Z 0; Halves and Groups stand in their own rows toward -Z, clear of each other")
    void rowZ() {
        assertEquals(0, CarriagePlotRows.rowZ(Row.ROOMS));
        assertEquals(-CarriagePlotRows.ROW_STEP_Z, CarriagePlotRows.rowZ(Row.HALVES));
        assertEquals(-2 * CarriagePlotRows.ROW_STEP_Z, CarriagePlotRows.rowZ(Row.GROUPS));
        assertTrue(CarriagePlotRows.ROW_STEP_Z > games.brennan.dungeontrain.train.CarriageDims.MAX_WIDTH,
            "a row step wider than any carriage keeps rows from touching");
    }

    @Test
    @DisplayName("Flatbeds and Portals stand in rows of their own, beyond the Groups")
    void fixedRows() {
        assertEquals(-3 * CarriagePlotRows.ROW_STEP_Z, CarriagePlotRows.rowZ(Row.FLATBEDS));
        assertEquals(-4 * CarriagePlotRows.ROW_STEP_Z, CarriagePlotRows.rowZ(Row.PORTALS));
        Map<String, Row> row = Map.of("standard", Row.ROOMS, "flatbed", Row.FLATBEDS,
            "portal", Row.PORTALS, "portal_short", Row.PORTALS);
        Map<String, Integer> len = Map.of("standard", 9, "flatbed", 9, "portal", 13, "portal_short", 9);
        CarriagePlotRows.Rows rows = CarriagePlotRows.rows(
            new String[] {"standard", "flatbed", "portal", "portal_short"}, row::get, len::get, 0);
        assertEquals(0, rows.startX().get("flatbed"), "the flatbed is alone in its row");
        assertEquals(0, rows.startX().get("portal"));
        assertEquals(13 + G, rows.startX().get("portal_short"));
    }

    @Test
    @DisplayName("an empty row's next plot goes at its start")
    void emptyRow() {
        CarriagePlotRows.Rows rows = CarriagePlotRows.rows(new String[] {"standard"},
            id -> Row.ROOMS, id -> 9, 0);
        assertEquals(0, rows.endOf(Row.HALVES));
        assertEquals(0, rows.endOf(Row.GROUPS));
    }
}
