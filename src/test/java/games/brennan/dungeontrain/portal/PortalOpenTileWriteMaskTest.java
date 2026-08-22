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
 * What an {@link PortalRoomMode#ENDLESS_OPEN} copy is allowed to write.
 *
 * <p>Endless Open repeats a floor and a roof and nothing between them. It used to get there by
 * stamping the whole room and stripping the interior back out, which placed and <i>filled</i> every
 * chest before breaking it — so each copy sprayed its loot across the floor. Now the interior is
 * masked out of the write, and nothing is placed to begin with.</p>
 *
 * <p>Two ways to get this wrong, and one test each. Mask too little and the loot comes back. Mask
 * the <b>clear</b> as well as the write and the copy keeps the rock it landed in, so the open space
 * is solid deepslate — which is why {@code stampRoomAt} takes the two masks separately and why the
 * clear mask must come back untouched.</p>
 */
class PortalOpenTileWriteMaskTest {

    private static final BlockPos ORIGIN = new BlockPos(200, -60, -30);

    private static PortalStructure structure(PortalRoomMode mode, Vec3i roomSize) {
        return PortalStructure.withMode(ORIGIN, "default", roomSize, mode, PortalRoomTiling.base());
    }

    /** The room boxes worth sweeping — the built-in room, the new floors, and a tall one. */
    private static Vec3i[] roomSizes() {
        CarriageDims dims = CarriageDims.DEFAULT;
        return new Vec3i[]{
            PortalRoomLayout.builtInSize(dims),                       // 11 x 7 x 13
            PortalRoomLayout.minSize(dims),                           // 5 x 7 x 9 — the new width floor
            new Vec3i(PortalRoomLayout.MIN_LENGTH, PortalRoomLayout.MIN_HEIGHT, 9),
            new Vec3i(PortalRoomLayout.MAX_LENGTH, PortalRoomLayout.MAX_HEIGHT, 15),
        };
    }

    @Test
    @DisplayName("Endless Open masks every interior cell out of the write, and no floor or roof cell")
    void endlessOpen_masksTheInteriorOnly() {
        for (Vec3i size : roomSizes()) {
            PortalStructure structure = structure(PortalRoomMode.ENDLESS_OPEN, size);
            PortalCorridorMask write = PortalRoomTiler.writeMaskFor(
                structure, PortalCorridorMask.NONE, ORIGIN, size, /*singlePlanes*/ false);

            for (int x = 0; x < size.getX(); x++) {
                for (int z = 0; z < size.getZ(); z++) {
                    for (int y = 0; y < size.getY(); y++) {
                        int wx = ORIGIN.getX() + x;
                        int wy = ORIGIN.getY() + y;
                        int wz = ORIGIN.getZ() + z;
                        boolean floorOrRoof = y == 0 || y == size.getY() - 1;
                        String where = size + " at (" + x + "," + y + "," + z + ")";
                        if (floorOrRoof) {
                            assertFalse(write.covers(wx, wy, wz),
                                where + ": the floor and the roof are what Endless Open repeats — "
                                    + "masking them leaves the copy with no blocks at all");
                        } else {
                            assertTrue(write.covers(wx, wy, wz),
                                where + ": an unmasked interior cell is a block placed and then "
                                    + "broken — for a chest, that is loot on the floor");
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("The clear mask is never widened — the interior still gets emptied of rock")
    void endlessOpen_leavesTheClearMaskAlone() {
        Vec3i size = PortalRoomLayout.builtInSize(CarriageDims.DEFAULT);
        PortalStructure structure = structure(PortalRoomMode.ENDLESS_OPEN, size);

        PortalCorridorMask clear = PortalCorridorMask.NONE;
        PortalCorridorMask write = PortalRoomTiler.writeMaskFor(
            structure, clear, ORIGIN, size, /*singlePlanes*/ false);

        assertTrue(clear.isEmpty(),
            "widening the clear mask would leave the copy standing in the deepslate it landed in");
        assertFalse(write.isEmpty(), "precondition: the write mask did gain the interior box");
    }

    @Test
    @DisplayName("Endless Open keeps masking the corridors too — the interior box is added, not substituted")
    void endlessOpen_unionsWithTheCorridorMask() {
        Vec3i size = PortalRoomLayout.builtInSize(CarriageDims.DEFAULT);
        PortalStructure structure = structure(PortalRoomMode.ENDLESS_OPEN, size);

        // A stand-in for the corridor row's mask, deliberately outside the room box so the two
        // cannot be confused for one another.
        BoundingBox corridor = new BoundingBox(0, 0, 0, 4, 4, 4);
        PortalCorridorMask write = PortalRoomTiler.writeMaskFor(
            structure, PortalCorridorMask.NONE.plus(corridor), ORIGIN, size,
            /*singlePlanes*/ false);

        assertTrue(write.covers(2, 2, 2), "the corridor box must survive the union");
        assertTrue(write.covers(ORIGIN.getX(), ORIGIN.getY() + 1, ORIGIN.getZ()),
            "the interior box must be there as well");
        assertEquals(2, write.boxes().size(), "one corridor box plus one interior box");
    }

    @Test
    @DisplayName("Single masks the whole tile — planes included, since they are written afterwards")
    void endlessOpen_singleMasksTheWholeTile() {
        for (Vec3i size : roomSizes()) {
            PortalStructure structure = structure(PortalRoomMode.ENDLESS_OPEN, size);
            PortalCorridorMask write = PortalRoomTiler.writeMaskFor(
                structure, PortalCorridorMask.NONE, ORIGIN, size, /*singlePlanes*/ true);

            for (int x = 0; x < size.getX(); x++) {
                for (int z = 0; z < size.getZ(); z++) {
                    for (int y = 0; y < size.getY(); y++) {
                        assertTrue(write.covers(ORIGIN.getX() + x, ORIGIN.getY() + y,
                                ORIGIN.getZ() + z),
                            size + " at (" + x + "," + y + "," + z + "): under Single the stamp puts "
                                + "nothing anywhere in the tile — the two planes are written from "
                                + "one block afterwards, and an authored floor laid here would only "
                                + "be overwritten");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Single still leaves the clear mask alone — the tile is emptied of rock as ever")
    void endlessOpen_singleLeavesTheClearMaskAlone() {
        Vec3i size = PortalRoomLayout.builtInSize(CarriageDims.DEFAULT);
        PortalStructure structure = structure(PortalRoomMode.ENDLESS_OPEN, size);

        PortalCorridorMask clear = PortalCorridorMask.NONE;
        PortalCorridorMask write = PortalRoomTiler.writeMaskFor(
            structure, clear, ORIGIN, size, /*singlePlanes*/ true);

        assertTrue(clear.isEmpty(),
            "widening the clear mask would leave the copy standing in the deepslate it landed in");
        assertFalse(write.isEmpty(), "precondition: the write mask did gain the whole tile box");
    }

    @Test
    @DisplayName("A mode that is not Endless Open ignores singlePlanes entirely")
    void otherModes_ignoreSinglePlanes() {
        Vec3i size = PortalRoomLayout.builtInSize(CarriageDims.DEFAULT);
        PortalCorridorMask clear = PortalCorridorMask.NONE.plus(new BoundingBox(0, 0, 0, 4, 4, 4));

        for (PortalRoomMode mode : PortalRoomMode.values()) {
            if (mode == PortalRoomMode.ENDLESS_OPEN) continue;
            // Single is Endless Open's alone (PortalRoomMode.singleCopiesApply), so the flag can
            // never be true here in practice — but the mode check has to come first regardless, or
            // an Endless Repetition tile would mask its whole room and stamp an empty box.
            assertSame(clear, PortalRoomTiler.writeMaskFor(
                    structure(mode, size), clear, ORIGIN, size, /*singlePlanes*/ true),
                mode + " tiles the whole room and must write all of it");
        }
    }

    @Test
    @DisplayName("Every other mode writes exactly what it clears")
    void otherModes_writeMaskIsTheClearMask() {
        Vec3i size = PortalRoomLayout.builtInSize(CarriageDims.DEFAULT);
        PortalCorridorMask clear = PortalCorridorMask.NONE.plus(new BoundingBox(0, 0, 0, 4, 4, 4));

        for (PortalRoomMode mode : PortalRoomMode.values()) {
            if (mode == PortalRoomMode.ENDLESS_OPEN) continue;
            PortalCorridorMask write = PortalRoomTiler.writeMaskFor(
                structure(mode, size), clear, ORIGIN, size, /*singlePlanes*/ false);
            // Same object, not merely equal: nothing is added, so nothing is rebuilt.
            assertSame(clear, write, mode + " tiles the whole room and must write all of it");
        }
    }

    @Test
    @DisplayName("plus() unions rather than replaces")
    void plus_unions() {
        BoundingBox first = new BoundingBox(0, 0, 0, 1, 1, 1);
        BoundingBox second = new BoundingBox(10, 10, 10, 11, 11, 11);

        PortalCorridorMask one = PortalCorridorMask.NONE.plus(first);
        assertTrue(one.covers(1, 1, 1));
        assertFalse(one.covers(10, 10, 10));

        PortalCorridorMask two = one.plus(second);
        assertTrue(two.covers(1, 1, 1), "the first box must survive");
        assertTrue(two.covers(10, 10, 10), "the second box must be added");
        assertEquals(1, one.boxes().size(), "plus must not mutate the mask it was called on");
    }
}
