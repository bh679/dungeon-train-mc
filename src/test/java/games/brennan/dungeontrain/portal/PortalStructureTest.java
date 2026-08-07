package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The exit twin's position is derived here and nowhere else. Everything that reads it — where the
 * corridor is stamped, how far the erase reaches, the occupancy box, and the origin the EXIT role's
 * frames map into — has to agree, so these tests pin the arithmetic rather than any one caller.
 */
class PortalStructureTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;   // length 9
    private static final BlockPos ORIGIN = new BlockPos(200, -60, -30);

    private static PortalStructure withRoomLength(int length) {
        return new PortalStructure(ORIGIN, "default",
            PortalRoomLayout.sizeOfLength(DIMS, length));
    }

    @Test
    @DisplayName("Built-in room: the offset is the old dims.length() + POCKET_LENGTH figure")
    void builtInRoom_reproducesTheOldConstant() {
        PortalStructure s = new PortalStructure(ORIGIN, "default", PortalRoomLayout.builtInSize(DIMS));
        assertEquals(9 + 11, s.exitTwinOffsetX(DIMS));
        assertEquals(ORIGIN.offset(20, 0, 0), s.exitOrigin(DIMS));
        assertEquals(20 + 9, s.spanX(DIMS));
    }

    @Test
    @DisplayName("A longer room pushes the exit twin further along — no overlap, no gap")
    void longerRoom_movesTheExitTwin() {
        PortalStructure shortRoom = withRoomLength(7);
        PortalStructure longRoom = withRoomLength(21);

        assertEquals(9 + 7, shortRoom.exitTwinOffsetX(DIMS));
        assertEquals(9 + 21, longRoom.exitTwinOffsetX(DIMS));

        // The room must exactly fill the gap between the two corridors: entry corridor ends at
        // origin.x + length - 1, exit corridor starts at exitOrigin.x, and the room occupies
        // everything strictly between.
        for (PortalStructure s : new PortalStructure[]{shortRoom, longRoom}) {
            int entryEnd = s.origin().getX() + DIMS.length() - 1;
            int exitStart = s.exitOrigin(DIMS).getX();
            assertEquals(s.roomLength(), exitStart - entryEnd - 1,
                "room of length " + s.roomLength() + " must exactly bridge the two corridors");
        }
    }

    @Test
    @DisplayName("The room begins where the entry corridor ends, whatever its length")
    void roomOrigin_abutsTheEntryCorridor() {
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS);
        for (int length : new int[]{PortalRoomLayout.MIN_LENGTH, 11, 21, PortalRoomLayout.MAX_LENGTH}) {
            PortalStructure s = withRoomLength(length);
            assertEquals(ORIGIN.getX() + DIMS.length(), s.roomOrigin(DIMS, layout).getX());
        }
    }

    @Test
    @DisplayName("Relocating keeps the room identity — only the position moves")
    void movedTo_keepsTheRoom() {
        PortalStructure s = withRoomLength(15);
        BlockPos elsewhere = ORIGIN.offset(64, 0, 0);
        PortalStructure moved = s.movedTo(elsewhere);

        assertEquals(elsewhere, moved.origin());
        assertEquals(s.roomName(), moved.roomName());
        assertEquals(s.roomSize(), moved.roomSize());
        assertEquals(s.spanX(DIMS), moved.spanX(DIMS));
        assertNotEquals(s.exitOrigin(DIMS), moved.exitOrigin(DIMS));
    }

    @Test
    @DisplayName("Rejects null components — a half-built structure would stamp into nowhere")
    void rejectsNull() {
        Vec3i size = PortalRoomLayout.builtInSize(DIMS);
        assertThrows(NullPointerException.class, () -> new PortalStructure(null, "default", size));
        assertThrows(NullPointerException.class, () -> new PortalStructure(ORIGIN, null, size));
        assertThrows(NullPointerException.class, () -> new PortalStructure(ORIGIN, "default", null));
    }
}
