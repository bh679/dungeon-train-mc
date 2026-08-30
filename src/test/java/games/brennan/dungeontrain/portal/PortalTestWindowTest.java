package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window a test dimensional carriage keeps its room copies for.
 *
 * <p>What these pin is the one thing the box got wrong: it measured the room off the CORRIDOR LANE,
 * which is the room's own floor only while the door sits at it. An author who had moved a doorway
 * was then standing "outside" their own room — and outside is the drain signal, so the copies came
 * down around them as they walked. Everything here is arithmetic on a structure, no level needed.</p>
 */
class PortalTestWindowTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;
    private static final BlockPos ORIGIN = new BlockPos(120, -60, 512);
    private static final PortalCarriageLayout LAYOUT =
        PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.DEFAULT);

    /** A room with plenty of height slack to spend on a door offset, and width slack for the Z one. */
    private static final Vec3i ROOM = new Vec3i(11, PortalRoomLayout.minHeight(DIMS) + 6,
        PortalRoomLayout.minWidth(DIMS) + 6);

    private static PortalStructure structure(PortalRoomSettings settings) {
        return new PortalStructure(ORIGIN, "default", ROOM, settings, PortalRoomTiling.base(),
            PortalExitCopies.NONE, PortalRoomTiling.Tile.BASE, PortalCorridorKind.DEFAULT);
    }

    /** Where a player stands on the floor of the room itself — one row above its floor. */
    private static double standingInRoomY(PortalStructure s) {
        return s.roomOrigin(DIMS, LAYOUT).getY() + 1;
    }

    /** Mid-room, so only Y is ever the reason a case fails. */
    private static double midRoomX(PortalStructure s) {
        return s.roomOrigin(DIMS, LAYOUT).getX() + s.roomLength() / 2.0;
    }

    private static double midRoomZ(PortalStructure s) {
        return s.roomOrigin(DIMS, LAYOUT).getZ() + s.roomWidth() / 2.0;
    }

    @Test
    @DisplayName("A mirrored room: the author standing in it is inside the window")
    void mirroredRoom_authorIsInside() {
        PortalStructure s = structure(PortalRoomSettings.DEFAULT);
        BoundingBox box = PortalTestWindow.occupancyBox(s, DIMS, LAYOUT);

        assertTrue(PortalTestWindow.contains(box, midRoomX(s), standingInRoomY(s), midRoomZ(s)));
        // The lane and the room's floor are the same row here, which is the case that always worked.
        assertTrue(PortalTestWindow.contains(box, midRoomX(s), ORIGIN.getY() + 1, midRoomZ(s)));
    }

    @Test
    @DisplayName("A raised entry door: the room's own floor is inside, not below the box")
    void raisedEntryDoor_roomFloorIsInside() {
        int offset = 4;
        PortalStructure s = structure(PortalRoomSettings.DEFAULT
            .withDoorHeightOffset(new PortalRoomDoorHeightOffset(offset)));

        // The premise: this room's floor really is below the corridor lane.
        assertEquals(ORIGIN.getY() - offset, s.roomOrigin(DIMS, LAYOUT).getY());

        BoundingBox box = PortalTestWindow.occupancyBox(s, DIMS, LAYOUT);
        assertTrue(PortalTestWindow.contains(box, midRoomX(s), standingInRoomY(s), midRoomZ(s)),
            "an author on the floor of their own room must not read as outside the window");
    }

    @Test
    @DisplayName("An exit door dropped below the entry one: its corridor is inside")
    void droppedExitDoor_exitCorridorIsInside() {
        // Entry door raised, exit door left at the room's floor — the exit corridor stands below the
        // entry lane by the whole of the entry door's offset.
        PortalStructure s = structure(PortalRoomSettings.DEFAULT
            .withDoorHeightOffset(new PortalRoomDoorHeightOffset(4)));
        BlockPos exit = s.exitOrigin(DIMS);
        assertEquals(ORIGIN.getY() - 4, exit.getY());

        BoundingBox box = PortalTestWindow.occupancyBox(s, DIMS, LAYOUT);
        assertTrue(PortalTestWindow.contains(box,
                exit.getX() + PortalCorridorSize.corridorLength(DIMS, PortalCorridorKind.DEFAULT) / 2.0,
                exit.getY() + 1, exit.getZ() + DIMS.width() / 2.0),
            "a player walking the displaced exit corridor is still in the structure");
    }

    @Test
    @DisplayName("The drain still drains: well above, below and past the structure is outside")
    void farOutside_stillDrains() {
        PortalStructure s = structure(PortalRoomSettings.DEFAULT
            .withDoorHeightOffset(new PortalRoomDoorHeightOffset(4)));
        BoundingBox box = PortalTestWindow.occupancyBox(s, DIMS, LAYOUT);

        assertFalse(PortalTestWindow.contains(box, midRoomX(s),
            s.roomOrigin(DIMS, LAYOUT).getY() + ROOM.getY() + 8, midRoomZ(s)), "above the ceiling");
        assertFalse(PortalTestWindow.contains(box, midRoomX(s),
            s.roomOrigin(DIMS, LAYOUT).getY() - 8, midRoomZ(s)), "below the floor");
        assertFalse(PortalTestWindow.contains(box, ORIGIN.getX() + s.spanX(DIMS) + 64,
            standingInRoomY(s), midRoomZ(s)), "past the exit corridor");
        assertFalse(PortalTestWindow.contains(box, midRoomX(s), standingInRoomY(s),
            ORIGIN.getZ() + 64), "off the side of the room");
    }

    @Test
    @DisplayName("An off-centre doorway does not move the window off the room in Z")
    void offCentreDoor_roomIsStillInside() {
        PortalStructure s = structure(PortalRoomSettings.DEFAULT
            .withDoorOffset(new PortalRoomDoorOffset(3))
            .withExitDoorOffset(new PortalRoomDoorOffset(-3)));
        BoundingBox box = PortalTestWindow.occupancyBox(s, DIMS, LAYOUT);

        BlockPos room = s.roomOrigin(DIMS, LAYOUT);
        assertTrue(PortalTestWindow.contains(box, midRoomX(s), standingInRoomY(s), room.getZ() + 0.5),
            "the room's near wall");
        assertTrue(PortalTestWindow.contains(box, midRoomX(s), standingInRoomY(s),
            room.getZ() + s.roomWidth() - 0.5), "the room's far wall");

        BlockPos exit = s.exitOrigin(DIMS);
        assertTrue(PortalTestWindow.contains(box,
                exit.getX() + 0.5, exit.getY() + 1, exit.getZ() + DIMS.width() / 2.0),
            "the displaced exit corridor");
    }
}
