package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    private static final int CORRIDOR_LENGTH = PortalCorridorSize.corridorLength(DIMS, PortalCorridorKind.LONG);

    private static PortalStructure structure() {
        return structure(DimensionalCarriageLayout.builtInSize(DIMS));
    }

    private static PortalStructure structure(Vec3i roomSize) {
        return PortalStructure.withMode(ORIGIN, "default", roomSize,
            DimensionalCarriageMode.ENDLESS_REPETITION, DimensionalCarriageTiling.base());
    }

    private static PortalCorridorMask mask() {
        return mask(structure());
    }

    private static PortalCorridorMask mask(PortalStructure structure) {
        return PortalCorridorMask.forStructure(
            structure, DIMS, PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.LONG), PLUG_DEPTH);
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
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.LONG);
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
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.LONG);
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
        Vec3i tall = DimensionalCarriageLayout.clampSize(DIMS,
            DimensionalCarriageLayout.builtInSize(DIMS).offset(0, 3, 0));
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
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.LONG);
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
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.LONG);
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
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.LONG);
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
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.LONG);
        PortalCorridorMask mask = mask();
        BlockPos neighbour = s.tileOrigin(DIMS, layout, new DimensionalCarriageTiling.Tile(-1, 1));

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
            s.exitOrigin(DIMS).getX() + PortalCorridorSize.corridorLength(DIMS, PortalCorridorKind.LONG) + PLUG_DEPTH, y, z));
    }

    @Test
    @DisplayName("The empty mask covers nothing, so every other row stamps exactly as it did")
    void noneCoversNothing() {
        assertTrue(PortalCorridorMask.NONE.isEmpty());
        assertFalse(PortalCorridorMask.NONE.covers(0, 0, 0));
        assertFalse(PortalCorridorMask.NONE.covers(ORIGIN));
    }

    // ---- one corridor at a time, for the extra corridors an endless room lays ----

    @Test
    @DisplayName("The two halves union to exactly the pair's mask — the split changed nothing")
    void perCorridorHalvesUnionToThePair() {
        PortalStructure s = structure();
        PortalCorridorMask pair = mask();
        PortalCorridorMask halves = corridor(s, PortalCarriageRole.ENTRY)
            .plus(corridor(s, PortalCarriageRole.EXIT));

        // Cell by cell over everything the structure spans, plus the plugs and a margin either side:
        // the point of the split is that a copy can be masked on its own, and it is only safe if the
        // two together are still the box the pair always had.
        BoundingBox span = new BoundingBox(
            s.origin().getX() - PLUG_DEPTH - 2, ORIGIN.getY() - 1, s.roomOrigin(DIMS, layout()).getZ() - 2,
            s.origin().getX() + s.spanX(DIMS) + PLUG_DEPTH + 2, ORIGIN.getY() + DIMS.height() + 1,
            s.roomOrigin(DIMS, layout()).getZ() + s.roomWidth() + 2);
        for (int x = span.minX(); x <= span.maxX(); x++) {
            for (int y = span.minY(); y <= span.maxY(); y++) {
                for (int z = span.minZ(); z <= span.maxZ(); z++) {
                    assertTrue(pair.covers(x, y, z) == halves.covers(x, y, z),
                        "disagreement at " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    @DisplayName("An entry copy is masked on the low-X side of its tile, an exit copy on the high-X side")
    void aCopySitsBesideItsAnchorTile() {
        PortalStructure s = structure();
        DimensionalCarriageTiling.Tile anchor = new DimensionalCarriageTiling.Tile(8, 0);
        PortalStructure shadow = s.shadowAt(anchor);

        // The identity the whole feature rests on: the shadow's ROOM slot is the anchor tile.
        assertEquals(s.tileOrigin(DIMS, layout(), anchor), shadow.roomOrigin(DIMS, layout()));

        int y = ORIGIN.getY() + 1;
        int z = shadow.origin().getZ() + 1;
        int tileMinX = shadow.roomOrigin(DIMS, layout()).getX();

        // Entry: the corridor runs up to the tile, its mouth one block short of it.
        PortalCorridorMask entry = corridor(shadow, PortalCarriageRole.ENTRY);
        assertTrue(entry.covers(tileMinX - 1, y, z), "the entry copy's mouth faces its tile");
        assertTrue(entry.covers(tileMinX - CORRIDOR_LENGTH, y, z), "the entry copy's far end");
        assertFalse(entry.covers(tileMinX + 1, y, z), "an entry copy never reaches into its own tile");

        // Exit: the corridor starts where the tile ends.
        PortalCorridorMask exit = corridor(shadow, PortalCarriageRole.EXIT);
        int tileMaxX = tileMinX + s.roomLength() - 1;
        assertTrue(exit.covers(tileMaxX + 1, y, z), "the exit copy's mouth faces its tile");
        assertTrue(exit.covers(tileMaxX + CORRIDOR_LENGTH, y, z), "the exit copy's far end");
        assertFalse(exit.covers(tileMinX + 1, y, z), "an exit copy never reaches into its own tile");
    }

    @Test
    @DisplayName("A copy's mask is the pair's mask, moved — it is the same corridor somewhere else")
    void aCopyIsThePairTranslated() {
        PortalStructure s = structure();
        DimensionalCarriageTiling.Tile anchor = new DimensionalCarriageTiling.Tile(3, -2);
        int dx = anchor.x() * s.roomLength();
        int dz = anchor.z() * s.roomWidth();

        for (PortalCarriageRole role : PortalCarriageRole.values()) {
            PortalCorridorMask here = corridor(s, role);
            PortalCorridorMask there = corridor(s.shadowAt(anchor), role);
            BoundingBox a = here.bounds();
            BoundingBox b = there.bounds();
            assertEquals(a.minX() + dx, b.minX(), role.name());
            assertEquals(a.maxX() + dx, b.maxX(), role.name());
            assertEquals(a.minZ() + dz, b.minZ(), role.name());
            assertEquals(a.maxZ() + dz, b.maxZ(), role.name());
            assertEquals(a.minY(), b.minY(), role.name());
            assertEquals(a.maxY(), b.maxY(), role.name());
        }
    }

    @Test
    @DisplayName("Unioning masks keeps both, and the union with nothing is the original")
    void unionKeepsBoth() {
        PortalStructure s = structure();
        PortalCorridorMask entry = corridor(s, PortalCarriageRole.ENTRY);
        assertSame(entry, entry.plus(PortalCorridorMask.NONE));
        assertSame(entry, PortalCorridorMask.NONE.plus(entry));

        PortalCorridorMask far = corridor(s.shadowAt(new DimensionalCarriageTiling.Tile(6, 0)),
            PortalCarriageRole.EXIT);
        PortalCorridorMask both = entry.plus(far);
        int y = ORIGIN.getY() + 1;
        int z = s.origin().getZ() + 1;
        assertTrue(both.covers(s.origin().getX(), y, z), "the union kept the near corridor");
        assertTrue(both.covers(far.bounds().minX(), y, z), "the union kept the far one");
    }

    private static PortalCarriageLayout layout() {
        return PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.LONG);
    }

    private static PortalCorridorMask corridor(PortalStructure structure, PortalCarriageRole role) {
        return PortalCorridorMask.forCorridor(structure, DIMS, layout(), PLUG_DEPTH, role);
    }
}
