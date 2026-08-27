package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalRoomDoorCellsTest {

    private static final CarriageDims DEFAULT_DIMS = CarriageDims.DEFAULT;   // 9 × 7 × 7

    @Test
    @DisplayName("Four cells: a two-block column just outside each ±X end, on the walkway centre")
    void forRoom_putsTwoCellsAtEachEnd() {
        BlockPos origin = new BlockPos(100, -60, 40);
        Vec3i size = PortalRoomLayout.builtInSize(DEFAULT_DIMS);            // 11 × 7 × 13

        List<BlockPos> cells = PortalRoomDoorCells.forRoom(origin, size);

        assertEquals(PortalRoomDoorCells.CELLS_PER_ROOM, cells.size());

        // Z = origin.z + 1 + (13 - 2) / 2 = 40 + 1 + 5 = 46; Y = the two rows above the floor.
        assertEquals(List.of(
            new BlockPos(99, -59, 46),
            new BlockPos(99, -58, 46),
            new BlockPos(111, -59, 46),
            new BlockPos(111, -58, 46)), cells);
    }

    @Test
    @DisplayName("doorBases names one cell per door — the lower half, with the upper implied above it")
    void doorBases_areTheLowerCellOfEachDoor() {
        BlockPos origin = new BlockPos(100, -60, 40);
        Vec3i size = PortalRoomLayout.builtInSize(DEFAULT_DIMS);

        List<BlockPos> bases = PortalRoomDoorCells.doorBases(origin, size);
        List<BlockPos> all = PortalRoomDoorCells.forRoom(origin, size);

        assertEquals(2, bases.size(), "one base per door, not one per cell");
        assertEquals(List.of(new BlockPos(99, -59, 46), new BlockPos(111, -59, 46)), bases);

        // The contract the renderer leans on: base plus the block above it is exactly the door.
        for (BlockPos base : bases) {
            assertTrue(all.contains(base), base + " must be a door cell");
            assertTrue(all.contains(base.above()), base.above() + " must be the door's upper half");
        }
        assertEquals(all.size(), bases.size() * PortalRoomDoorCells.CELLS_PER_DOOR);
    }

    @Test
    @DisplayName("The door line is the same line roomOrigin centres the interior on, at every legal width")
    void doorZ_agreesWithRoomOriginCentring() {
        BlockPos entry = new BlockPos(0, 0, 0);

        // The same sweep shape PortalRoomLayoutTest uses, and for the same reason: minWidth is the
        // exact geometric bound, so the agreement has to hold at every carriage width a portal can
        // exist at, odd and even — not at one sampled width with slack to spare. If roomOrigin's
        // centring ever changes, this fails here rather than showing up as a door opening onto a
        // wall in someone's world.
        for (int carriageWidth = PortalCarriageLayout.MIN_WIDTH;
             carriageWidth <= CarriageDims.MAX_WIDTH; carriageWidth++) {
            CarriageDims dims = CarriageDims.clamp(9, carriageWidth, 7);
            PortalCarriageLayout layout =
                PortalCarriageBuilder.layoutFor(dims, PortalCorridorKind.LONG);

            for (int width : new int[]{PortalRoomLayout.minWidth(dims),
                                       PortalRoomLayout.minWidth(dims) + 1,
                                       PortalRoomLayout.minWidth(dims) + 6}) {
                BlockPos room = PortalRoomLayout.roomOrigin(entry, dims, layout, width);
                Vec3i size = new Vec3i(PortalRoomLayout.BUILT_IN_LENGTH,
                    PortalRoomLayout.minHeight(dims), width);

                assertEquals(entry.getZ() + layout.doorZ(),
                    PortalRoomDoorCells.doorZ(room, size),
                    "carriage width " + carriageWidth + ", room width " + width
                        + ": the ghosted line must be the corridor's doorway line");
            }
        }
    }

    @Test
    @DisplayName("The door line agrees with an off-centre roomOrigin too, at every legal offset")
    void doorZ_agreesWithRoomOriginCentring_offCentre() {
        BlockPos entry = new BlockPos(0, 0, 0);
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DEFAULT_DIMS, PortalCorridorKind.LONG);

        // Comfortably wider than the floor, so every offset tried actually has slack to spend.
        int width = PortalRoomLayout.minWidth(DEFAULT_DIMS) + 10;
        int max = PortalRoomLayout.maxDoorOffset(DEFAULT_DIMS, width);
        Vec3i size = new Vec3i(PortalRoomLayout.BUILT_IN_LENGTH, PortalRoomLayout.minHeight(DEFAULT_DIMS), width);

        for (int offset = -max; offset <= max; offset++) {
            BlockPos room = PortalRoomLayout.roomOrigin(entry, DEFAULT_DIMS, layout, width, offset);
            assertEquals(entry.getZ() + layout.doorZ(), PortalRoomDoorCells.doorZ(room, size, offset),
                "offset " + offset + ": the ghosted line must still be the corridor's own fixed line");
        }
    }

    @Test
    @DisplayName("A door cell is the corridor's own doorway cell, in the room's frame")
    void forRoom_landsOnTheCorridorsDoorwayColumn() {
        BlockPos entry = new BlockPos(-300, 12, 88);
        PortalCarriageLayout layout =
            PortalCarriageBuilder.layoutFor(DEFAULT_DIMS, PortalCorridorKind.LONG);
        int width = PortalRoomLayout.minWidth(DEFAULT_DIMS);
        BlockPos room = PortalRoomLayout.roomOrigin(entry, DEFAULT_DIMS, layout, width);
        Vec3i size = new Vec3i(PortalRoomLayout.BUILT_IN_LENGTH,
            PortalRoomLayout.minHeight(DEFAULT_DIMS), width);

        List<BlockPos> cells = PortalRoomDoorCells.forRoom(room, size);

        // The entry corridor's far door: its own local (farDoorX, dy, doorZ), converted to world.
        // Both cells of the column, and nothing else at that X.
        int farDoorWorldX = entry.getX() + layout.farDoorX();
        int doorWorldZ = entry.getZ() + layout.doorZ();
        for (int dy : new int[]{layout.floorY() + 1, layout.floorY() + 2}) {
            BlockPos expected = new BlockPos(farDoorWorldX, entry.getY() + dy, doorWorldZ);
            assertTrue(cells.contains(expected),
                "the corridor's own doorway cell " + expected + " must be ghosted; got " + cells);
            assertTrue(layout.isDoorwayCell(dy, layout.doorZ()),
                "sanity: dy " + dy + " really is a doorway cell of the corridor");
        }
    }

    @Test
    @DisplayName("Cells sit outside the plot in X and strictly inside it in Y and Z")
    void forRoom_straddlesThePlotBoundary() {
        BlockPos origin = new BlockPos(7, 64, -13);

        for (int width : new int[]{9, 10, 13, 24, 25}) {
            Vec3i size = new Vec3i(17, 8, width);
            for (BlockPos cell : PortalRoomDoorCells.forRoom(origin, size)) {
                int dx = cell.getX() - origin.getX();
                int dy = cell.getY() - origin.getY();
                int dz = cell.getZ() - origin.getZ();

                // Outside on X: the doors belong to the corridors, which are not in the plot.
                assertTrue(dx == -1 || dx == size.getX(),
                    "width " + width + ": " + cell + " must be one column off an end, was dx=" + dx);
                // Inside on Y and Z: a door standing on the room's floor, on its walkway.
                assertTrue(dy > 0 && dy < size.getY(),
                    "width " + width + ": " + cell + " must clear the floor row and the ceiling");
                assertTrue(dz > 0 && dz < size.getZ() - 1,
                    "width " + width + ": " + cell + " must be inside the room's side walls");
            }
        }
    }

    @Test
    @DisplayName("A degenerate box draws nothing rather than throwing — a render pass is not a validator")
    void forRoom_isEmptyForADegenerateBox() {
        BlockPos origin = BlockPos.ZERO;

        assertTrue(PortalRoomDoorCells.forRoom(origin, new Vec3i(0, 7, 13)).isEmpty());
        assertTrue(PortalRoomDoorCells.forRoom(origin, new Vec3i(11, 0, 13)).isEmpty());
        assertTrue(PortalRoomDoorCells.forRoom(origin, new Vec3i(11, 7, 2)).isEmpty());
        assertTrue(PortalRoomDoorCells.forRoom(origin, null).isEmpty());
        assertTrue(PortalRoomDoorCells.forRoom(null, new Vec3i(11, 7, 13)).isEmpty());
    }
}
