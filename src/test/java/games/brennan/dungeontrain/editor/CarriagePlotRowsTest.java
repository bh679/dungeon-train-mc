package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.ShellPool;
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
        Map<String, ShellPool> pool = Map.of(
            "standard", ShellPool.ROOM, "cargo", ShellPool.GROUP, "fancy", ShellPool.ROOM,
            "twin", ShellPool.HALF, "flatbedgroup", ShellPool.GROUP, "halfy", ShellPool.HALF);
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
        assertEquals(2 * (9 + G), rows.endOf(ShellPool.ROOM));
        assertEquals(2 * (27 + G), rows.endOf(ShellPool.GROUP));
        assertEquals(ShellPool.HALF, rows.rowOf().get("halfy"));
    }

    @Test
    @DisplayName("Rooms keep Z 0; Halves and Groups stand in their own rows toward -Z, clear of each other")
    void rowZ() {
        assertEquals(0, CarriagePlotRows.rowZ(ShellPool.ROOM));
        assertEquals(-CarriagePlotRows.ROW_STEP_Z, CarriagePlotRows.rowZ(ShellPool.HALF));
        assertEquals(-2 * CarriagePlotRows.ROW_STEP_Z, CarriagePlotRows.rowZ(ShellPool.GROUP));
        assertTrue(CarriagePlotRows.ROW_STEP_Z > games.brennan.dungeontrain.train.CarriageDims.MAX_WIDTH,
            "a row step wider than any carriage keeps rows from touching");
    }

    @Test
    @DisplayName("an empty row's next plot goes at its start")
    void emptyRow() {
        CarriagePlotRows.Rows rows = CarriagePlotRows.rows(new String[] {"standard"},
            id -> ShellPool.ROOM, id -> 9, 0);
        assertEquals(0, rows.endOf(ShellPool.HALF));
        assertEquals(0, rows.endOf(ShellPool.GROUP));
    }
}
