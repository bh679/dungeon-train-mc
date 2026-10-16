package games.brennan.dungeontrain.editor.workbench;

import games.brennan.dungeontrain.portal.PortalRoomCopiesPlot;
import games.brennan.dungeontrain.portal.PortalRoomCopiesVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The room name a staged dimensional carriage room answers to ({@link WorkbenchRooms}) — what lets
 * every name-keyed room path reach a room standing in the Workbench.
 */
final class WorkbenchRoomsTest {

    @Test
    @DisplayName("a staged id round-trips through its room name")
    void roundTrip() {
        assertEquals("workbench:hall-12", WorkbenchRooms.nameFor("hall-12"));
        assertEquals("hall-12", WorkbenchRooms.stagedIdOf(WorkbenchRooms.nameFor("hall-12")));
    }

    @Test
    @DisplayName("an ordinary room name, a bare prefix and null are not staged rooms")
    void ordinaryNames() {
        assertNull(WorkbenchRooms.stagedIdOf("default"));
        assertNull(WorkbenchRooms.stagedIdOf("workbench:"));
        assertNull(WorkbenchRooms.stagedIdOf(null));
    }

    @Test
    @DisplayName("a Copies key over a staged room reads its room and plane back whole, colon and all")
    void copiesKeyKeepsTheStagedName() {
        String room = WorkbenchRooms.nameFor("hall-12");
        String key = PortalRoomCopiesPlot.KEY_PREFIX + PortalRoomCopiesVariant.Plane.ROOF.id() + ":" + room;
        assertEquals(room, PortalRoomCopiesPlot.roomOf(key));
        assertEquals(PortalRoomCopiesVariant.Plane.ROOF, PortalRoomCopiesPlot.planeOf(key));
    }

    @Test
    @DisplayName("a staged Workbench plot key names its staged id")
    void variantPlotKey() {
        assertEquals("hall-12", WorkbenchVariantPlot.stagedIdOfKey(WorkbenchVariantPlot.keyFor("hall-12")));
        assertNull(WorkbenchVariantPlot.stagedIdOfKey("carriage:standard"));
        assertNull(WorkbenchVariantPlot.stagedIdOfKey("workbench:"));
    }
}
