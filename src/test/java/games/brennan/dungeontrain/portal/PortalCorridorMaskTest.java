package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
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

    /** A corridor is longer than the carriage it stands in — 13 at the default dims, not 9. */
    private static final int CORRIDOR_LENGTH = PortalCorridorSize.corridorLength(DIMS);

    private static PortalStructure structure() {
        return structure(PortalRoomLayout.builtInSize(DIMS));
    }

    private static PortalStructure structure(Vec3i roomSize) {
        return PortalStructure.withMode(ORIGIN, "default", roomSize,
            PortalRoomMode.ENDLESS_REPETITION, PortalRoomTiling.base());
    }

    private static PortalCorridorMask mask() {
        return mask(structure());
    }

    private static PortalCorridorMask mask(PortalStructure structure) {
        return PortalCorridorMask.forStructure(
            structure, DIMS, PortalCarriageBuilder.layoutFor(DIMS), PLUG_DEPTH);
    }

    /** The two planes the seal ring fills — a corridor's mouth, facing the room. */
    private static boolean isSealPlane(PortalStructure s, int x) {
        return x == s.origin().getX() + CORRIDOR_LENGTH - 1 || x == s.exitOrigin(DIMS).getX();
    }

    @Test
    @DisplayName("Every cell of both corridors is covered, along the CORRIDOR's length")
    void coversBothCorridors() {
        PortalStructure s = structure();
        PortalCorridorMask mask = mask();

        // The whole box, not one interior sample: a cell missed here is a hole a room copy punches in
        // a corridor that nothing will ever repair. The length is the corridor's own, which is longer
        // than the carriage it stands in.
        for (int dx = 0; dx < CORRIDOR_LENGTH; dx++) {
            for (int dz = 0; dz < DIMS.width(); dz++) {
                for (int dy = 0; dy < DIMS.height(); dy++) {
                    assertTrue(mask.covers(s.origin().getX() + dx, ORIGIN.getY() + dy,
                        ORIGIN.getZ() + dz), "entry corridor cell " + dx + "," + dy + "," + dz);
                    assertTrue(mask.covers(s.exitOrigin(DIMS).getX() + dx, ORIGIN.getY() + dy,
                        ORIGIN.getZ() + dz), "exit corridor cell " + dx + "," + dy + "," + dz);
                }
            }
        }
    }

    @Test
    @DisplayName("Both plugs are covered in full — written by the same step, never repaired either")
    void coversBothPlugs() {
        PortalStructure s = structure();
        PortalCorridorMask mask = mask();
        int exitPlugX = s.exitOrigin(DIMS).getX() + CORRIDOR_LENGTH;

        // plugBeyond runs one column proud of the corridor on each side in Z, so the sweep does too.
        for (int d = 1; d <= PLUG_DEPTH; d++) {
            for (int dz = -1; dz <= DIMS.width(); dz++) {
                for (int dy = 0; dy < DIMS.height(); dy++) {
                    int y = ORIGIN.getY() + dy;
                    int z = ORIGIN.getZ() + dz;
                    assertTrue(mask.covers(s.origin().getX() - d, y, z),
                        "entry plug at -" + d + " (" + dy + "," + dz + ")");
                    assertTrue(mask.covers(exitPlugX + d - 1, y, z),
                        "exit plug at +" + d + " (" + dy + "," + dz + ")");
                }
            }
        }
    }

    @Test
    @DisplayName("Each corridor's mouth is sealed across the room's full width and height")
    void coversTheSealRing() {
        PortalStructure s = structure();
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS);
        PortalCorridorMask mask = mask();
        BlockPos room = s.roomOrigin(DIMS, layout);

        // The mouth is the corridor's LAST cell — its own length, not the carriage's. (This test used
        // to probe dims.length() - 1, four blocks short of it, and passed only because the mask was
        // once an undifferentiated slab.) The seal fills the room's whole cross-section there, well
        // outside the corridor's own 7-wide box.
        int[] planes = {s.origin().getX() + CORRIDOR_LENGTH - 1, s.exitOrigin(DIMS).getX()};
        for (int planeX : planes) {
            for (int z = room.getZ(); z < room.getZ() + s.roomSize().getZ(); z++) {
                for (int y = ORIGIN.getY(); y < ORIGIN.getY() + s.roomSize().getY(); y++) {
                    assertTrue(mask.covers(planeX, y, z),
                        "seal cell at door plane " + planeX + " (" + y + ", " + z + ")");
                }
            }
        }
    }

    @Test
    @DisplayName("The aisles either side of a corridor are the room's to build in, not the corridor's")
    void leavesTheAislesBesideACorridorFree() {
        PortalStructure s = structure();
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS);
        PortalCorridorMask mask = mask();
        BlockPos room = s.roomOrigin(DIMS, layout);

        // This is the fix, stated: a copy landing on the corridor row has to be able to lay its
        // floor, its ceiling and its own outer walls beside the corridor. Reserving that space is the
        // same as leaving it void, which is what a player used to walk into.
        int[] starts = {s.origin().getX(), s.exitOrigin(DIMS).getX()};
        for (int start : starts) {
            for (int dx = 0; dx < CORRIDOR_LENGTH; dx++) {
                int x = start + dx;
                if (isSealPlane(s, x)) continue;   // the mouth is walled, deliberately
                for (int z = room.getZ(); z < room.getZ() + s.roomSize().getZ(); z++) {
                    boolean besideTheCorridor = z < ORIGIN.getZ()
                        || z >= ORIGIN.getZ() + DIMS.width();
                    if (!besideTheCorridor) continue;
                    for (int y = ORIGIN.getY(); y < ORIGIN.getY() + s.roomSize().getY(); y++) {
                        assertFalse(mask.covers(x, y, z),
                            "aisle cell beside a corridor at (" + x + ", " + y + ", " + z + ")");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("A tall room's space above a corridor is free too, so its interior is not truncated")
    void leavesTheSpaceAboveACorridorFree() {
        Vec3i tall = PortalRoomLayout.clampSize(DIMS,
            PortalRoomLayout.builtInSize(DIMS).offset(0, 3, 0));
        PortalStructure s = structure(tall);
        PortalCorridorMask mask = mask(s);
        int z = ORIGIN.getZ() + 1;   // squarely over the corridor

        for (int y = ORIGIN.getY() + DIMS.height(); y < ORIGIN.getY() + tall.getY(); y++) {
            for (int dx = 0; dx < CORRIDOR_LENGTH; dx++) {
                int x = s.origin().getX() + dx;
                if (isSealPlane(s, x)) continue;
                assertFalse(mask.covers(x, y, z), "cell above the entry corridor at " + x + ", " + y);
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
        // The exit corridor's own length, not the carriage's — the mask covers the whole corridor.
        assertFalse(mask.covers(
            s.exitOrigin(DIMS).getX() + PortalCorridorSize.corridorLength(DIMS) + PLUG_DEPTH, y, z));
    }

    @Test
    @DisplayName("The empty mask covers nothing, so every other row stamps exactly as it did")
    void noneCoversNothing() {
        assertTrue(PortalCorridorMask.NONE.isEmpty());
        assertFalse(PortalCorridorMask.NONE.covers(0, 0, 0));
        assertFalse(PortalCorridorMask.NONE.covers(ORIGIN));
    }
}
