package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The volume the twin corridors own.
 *
 * <p>A twin is placed once and must stay placed, so every later write from the endless tiling is
 * skipped inside this mask. These tests pin that it covers everything
 * {@code PortalCarriageBuilder.stampCorridors} writes — miss a cell and a copy of the room punches a
 * hole in a corridor that nothing will ever repair — and that it covers nothing beyond that, or the
 * endless room would have unexplained gaps in it.</p>
 */
class PortalCorridorMaskTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;   // 9 long, 7 tall, 7 wide
    private static final BlockPos ORIGIN = new BlockPos(200, -60, -30);
    private static final int PLUG_DEPTH = 3;

    private static PortalStructure structure() {
        return PortalStructure.withMode(ORIGIN, "default", PortalRoomLayout.builtInSize(DIMS),
            PortalRoomMode.ENDLESS_REPETITION, PortalRoomTiling.base());
    }

    private static PortalCorridorMask mask() {
        return PortalCorridorMask.forStructure(
            structure(), DIMS, PortalCarriageBuilder.layoutFor(DIMS), PLUG_DEPTH);
    }

    @Test
    @DisplayName("Both corridors are covered, along their whole length")
    void coversBothCorridors() {
        PortalStructure s = structure();
        PortalCorridorMask mask = mask();
        int y = ORIGIN.getY() + 1;
        int z = ORIGIN.getZ() + 1;

        for (int dx = 0; dx < DIMS.length(); dx++) {
            assertTrue(mask.covers(s.origin().getX() + dx, y, z), "entry corridor cell " + dx);
            assertTrue(mask.covers(s.exitOrigin(DIMS).getX() + dx, y, z), "exit corridor cell " + dx);
        }
    }

    @Test
    @DisplayName("Both plugs are covered — they are written by the same step and never repaired either")
    void coversBothPlugs() {
        PortalStructure s = structure();
        PortalCorridorMask mask = mask();
        int y = ORIGIN.getY() + 1;
        int z = ORIGIN.getZ() + 1;

        for (int d = 1; d <= PLUG_DEPTH; d++) {
            assertTrue(mask.covers(s.origin().getX() - d, y, z), "entry plug at -" + d);
            assertTrue(mask.covers(s.exitOrigin(DIMS).getX() + DIMS.length() - 1 + d, y, z),
                "exit plug at +" + d);
        }
    }

    @Test
    @DisplayName("The seal ring is covered: the corridor's slab runs the room's full width and height")
    void coversTheSealRing() {
        PortalStructure s = structure();
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS);
        PortalCorridorMask mask = mask();
        BlockPos room = s.roomOrigin(DIMS, layout);

        // The door plane sits at the corridor's last cell, and the seal fills the room's whole
        // cross-section there — well outside the corridor's own 7-wide, 7-tall box.
        int planeX = s.origin().getX() + DIMS.length() - 1;
        for (int z = room.getZ(); z < room.getZ() + s.roomSize().getZ(); z++) {
            for (int y = ORIGIN.getY(); y < ORIGIN.getY() + s.roomSize().getY(); y++) {
                assertTrue(mask.covers(planeX, y, z),
                    "seal cell at the entry door plane (" + y + ", " + z + ")");
            }
        }
    }

    @Test
    @DisplayName("The base room between the corridors is NOT covered — it is the room, not a corridor")
    void leavesTheRoomAlone() {
        PortalStructure s = structure();
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS);
        PortalCorridorMask mask = mask();
        BlockPos room = s.roomOrigin(DIMS, layout);

        for (int dx = 0; dx < s.roomLength(); dx++) {
            assertFalse(mask.covers(room.getX() + dx, ORIGIN.getY() + 1, room.getZ() + 1),
                "room cell " + dx + " must be writable — it is the room");
        }
    }

    @Test
    @DisplayName("The room's end columns face straight into a corridor, so nothing may settle them")
    void endColumnsFaceIntoACorridor() {
        PortalStructure s = structure();
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS);
        PortalCorridorMask mask = mask();
        BlockPos room = s.roomOrigin(DIMS, layout);
        int y = ORIGIN.getY() + 1;

        // The doorway the player walks through lives in these two columns. They sit one block inside
        // each door plane, so covers() is false for them — and closing that face would brick up the
        // way in. Both ends, right across the room's width.
        for (int dz = 0; dz < s.roomWidth(); dz++) {
            int z = room.getZ() + dz;
            BlockPos entryEnd = new BlockPos(room.getX(), y, z);
            BlockPos exitEnd = new BlockPos(room.getX() + s.roomLength() - 1, y, z);

            assertFalse(mask.covers(entryEnd), "the room's first column is the room, not a corridor");
            assertTrue(mask.facedBy(entryEnd, -1, 0), "entry door plane is one step -X of it");
            assertFalse(mask.covers(exitEnd), "the room's last column is the room, not a corridor");
            assertTrue(mask.facedBy(exitEnd, 1, 0), "exit door plane is one step +X of it");
        }
    }

    @Test
    @DisplayName("Nothing else faces a corridor: mid-room columns and both Z walls settle as before")
    void onlyTheEndColumnsFaceIntoACorridor() {
        PortalStructure s = structure();
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS);
        PortalCorridorMask mask = mask();
        BlockPos room = s.roomOrigin(DIMS, layout);
        int y = ORIGIN.getY() + 1;

        // One in from each end, and the ±Z walls the endless modes still have to open and close.
        BlockPos afterEntry = new BlockPos(room.getX() + 1, y, room.getZ() + 1);
        assertFalse(mask.facedBy(afterEntry, -1, 0), "one block further in is the room's own business");

        BlockPos beforeExit = new BlockPos(room.getX() + s.roomLength() - 2, y, room.getZ() + 1);
        assertFalse(mask.facedBy(beforeExit, 1, 0), "one block short of the exit likewise");

        for (int dx = 0; dx < s.roomLength(); dx++) {
            int x = room.getX() + dx;
            assertFalse(mask.facedBy(new BlockPos(x, y, room.getZ()), 0, -1),
                "the -Z wall at " + dx + " faces the rock, not a corridor");
            assertFalse(mask.facedBy(new BlockPos(x, y, room.getZ() + s.roomWidth() - 1), 0, 1),
                "the +Z wall at " + dx + " faces the rock, not a corridor");
        }
    }

    @Test
    @DisplayName("A copy one room off the corridor row is untouched — only that row meets a twin")
    void leavesOtherRowsAlone() {
        PortalStructure s = structure();
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS);
        PortalCorridorMask mask = mask();
        BlockPos neighbour = s.tileOrigin(DIMS, layout, new PortalRoomTiling.Tile(-1, 1));

        for (int dz = 0; dz < s.roomWidth(); dz++) {
            assertFalse(mask.covers(neighbour.getX() + 1, ORIGIN.getY() + 1, neighbour.getZ() + dz),
                "a copy on row 1 is clear of both corridors");
        }
    }

    @Test
    @DisplayName("Beyond the plugs is free again — the mask stops where the structure does")
    void stopsPastThePlugs() {
        PortalStructure s = structure();
        PortalCorridorMask mask = mask();
        int y = ORIGIN.getY() + 1;
        int z = ORIGIN.getZ() + 1;

        assertFalse(mask.covers(s.origin().getX() - PLUG_DEPTH - 1, y, z));
        assertFalse(mask.covers(s.exitOrigin(DIMS).getX() + DIMS.length() + PLUG_DEPTH, y, z));
    }

    @Test
    @DisplayName("The empty mask covers nothing, so every other row stamps exactly as it did")
    void noneCoversNothing() {
        assertTrue(PortalCorridorMask.NONE.isEmpty());
        assertFalse(PortalCorridorMask.NONE.covers(0, 0, 0));
        assertFalse(PortalCorridorMask.NONE.covers(ORIGIN));
    }
}
