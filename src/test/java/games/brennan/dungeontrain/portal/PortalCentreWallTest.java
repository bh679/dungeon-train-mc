package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The doorway column through the wall between a portal pair's two corridors — the blocks a severed
 * pair opens so its group can be walked through.
 *
 * <p>The property that actually matters is the one about <b>frames</b>: the wall is described in
 * middle-slot-local coordinates, but the code that opens it live holds a corridor pairing and can
 * only address cells in corridor-local ones. Those two frames differ by different amounts for the two
 * roles, and getting either wrong opens a hole somewhere else in the train — so most of this file is
 * about the two agreeing.</p>
 */
final class PortalCentreWallTest {

    /** The shipped carriage: 9 long, 7 wide, 7 high. Odd length, so a one-block centre wall. */
    private static final CarriageDims DEFAULT_DIMS = new CarriageDims(9, 7, 7);

    /** An even length, where {@link PortalCorridorSize#centreWallWidth} is two rather than one. */
    private static final CarriageDims EVEN_DIMS = new CarriageDims(10, 7, 7);

    @Test
    @DisplayName("the column spans the centre wall and is two blocks tall, on the walkway centre")
    void columnCoversTheWallAtDoorHeight() {
        for (CarriageDims dims : new CarriageDims[] {DEFAULT_DIMS, EVEN_DIMS}) {
            int width = PortalCorridorSize.centreWallWidth(dims, PortalCorridorKind.LONG);
            assertEquals(PortalCentreWall.minX(dims, PortalCorridorKind.LONG) + width, PortalCentreWall.maxXExclusive(dims, PortalCorridorKind.LONG),
                "the column spans exactly the wall at length " + dims.length());
            assertEquals(width * 2, PortalCentreWall.doorwayCells(dims, PortalCorridorKind.LONG).length,
                "two cells per wall column at length " + dims.length());

            // On the same line the corridors put their doors on, or the opening would meet a wall.
            PortalCarriageLayout layout =
                new PortalCarriageLayout(PortalCorridorSize.corridorLength(dims, PortalCorridorKind.LONG), dims.height(), dims.width());
            assertEquals(layout.doorZ(), PortalCentreWall.doorZ(dims),
                "the column must sit on the corridors' own doorway line");

            // The two cells a door occupies, and not the floor beneath them.
            for (int[] cell : PortalCentreWall.doorwayCells(dims, PortalCorridorKind.LONG)) {
                assertTrue(cell[1] >= 1 && cell[1] <= 2, "cell at dy=" + cell[1] + " is not door height");
                assertTrue(layout.isDoorwayCell(cell[1], cell[2]),
                    "cell (" + cell[1] + "," + cell[2] + ") is not a doorway cell of the corridor");
            }
        }
    }

    /** The floor stays solid: an opening that took it out would be a hole to walk into, not through. */
    @Test
    @DisplayName("the floor and the ceiling are not part of the column")
    void floorAndCeilingStaySolid() {
        int z = PortalCentreWall.doorZ(DEFAULT_DIMS);
        int x = PortalCentreWall.minX(DEFAULT_DIMS, PortalCorridorKind.LONG);

        assertFalse(PortalCentreWall.isDoorwayColumn(DEFAULT_DIMS, PortalCorridorKind.LONG, x, 0, z), "floor");
        assertFalse(PortalCentreWall.isDoorwayColumn(DEFAULT_DIMS, PortalCorridorKind.LONG, x, 3, z), "above the doorway");
        assertFalse(PortalCentreWall.isDoorwayColumn(DEFAULT_DIMS, PortalCorridorKind.LONG, x, DEFAULT_DIMS.height() - 1, z),
            "ceiling");
    }

    /** Everything off the walkway line, and everything outside the wall, belongs to somebody else. */
    @Test
    @DisplayName("nothing outside the wall's own X range, or off its Z line, is in the column")
    void onlyTheWallsOwnCells() {
        int z = PortalCentreWall.doorZ(DEFAULT_DIMS);

        assertFalse(PortalCentreWall.isDoorwayColumn(DEFAULT_DIMS, PortalCorridorKind.LONG,
            PortalCentreWall.minX(DEFAULT_DIMS, PortalCorridorKind.LONG) - 1, 1, z), "the entry corridor's overrun");
        assertFalse(PortalCentreWall.isDoorwayColumn(DEFAULT_DIMS, PortalCorridorKind.LONG,
            PortalCentreWall.maxXExclusive(DEFAULT_DIMS, PortalCorridorKind.LONG), 1, z), "the exit corridor's overrun");

        for (int dz = 0; dz < DEFAULT_DIMS.width(); dz++) {
            if (dz == z) continue;
            assertFalse(PortalCentreWall.isDoorwayColumn(
                DEFAULT_DIMS, PortalCorridorKind.LONG, PortalCentreWall.minX(DEFAULT_DIMS, PortalCorridorKind.LONG), 1, dz),
                "z=" + dz + " is wall, not doorway");
        }
    }

    /**
     * The frame conversion, which is the part that can silently open a hole in the wrong place.
     *
     * <p>Both corridors must name the <b>same</b> blocks of the train, so their corridor-local X
     * values, shifted back into the group's own frame, have to land on identical cells. The entry
     * corridor's origin is the group's first slot; the exit corridor's is two slots on, pulled back by
     * its overrun ({@link PortalCorridorSize#originOffsetX}).</p>
     */
    @Test
    @DisplayName("both corridors address the same blocks of the group")
    void bothRolesNameTheSameCells() {
        for (CarriageDims dims : new CarriageDims[] {DEFAULT_DIMS, EVEN_DIMS}) {
            int entryOriginInGroup = 0;
            int exitOriginInGroup = 2 * dims.length() + PortalCorridorSize.originOffsetX(
                PortalCarriageRole.EXIT, dims, PortalCorridorKind.LONG);

            Set<String> fromEntry = inGroupFrame(
                PortalCentreWall.doorwayCellsFromCorridor(dims, PortalCorridorKind.LONG, PortalCarriageRole.ENTRY),
                entryOriginInGroup);
            Set<String> fromExit = inGroupFrame(
                PortalCentreWall.doorwayCellsFromCorridor(dims, PortalCorridorKind.LONG, PortalCarriageRole.EXIT),
                exitOriginInGroup);
            // The middle slot is the second of the three, so its own origin is one length in.
            Set<String> fromMiddle = inGroupFrame(PortalCentreWall.doorwayCells(dims, PortalCorridorKind.LONG), dims.length());

            assertEquals(fromMiddle, fromEntry, "entry frame at length " + dims.length());
            assertEquals(fromMiddle, fromExit, "exit frame at length " + dims.length());
        }
    }

    /**
     * The wall sits between the two corridors and touches neither — which is what makes it reachable
     * only through the frame conversion above, and why nothing mirrors when it is opened.
     */
    @Test
    @DisplayName("the column is outside both corridors' own bounds")
    void theColumnBelongsToNeitherCorridor() {
        for (CarriageDims dims : new CarriageDims[] {DEFAULT_DIMS, EVEN_DIMS}) {
            int corridorLength = PortalCorridorSize.corridorLength(dims, PortalCorridorKind.LONG);

            for (int[] cell : PortalCentreWall.doorwayCellsFromCorridor(dims, PortalCorridorKind.LONG, PortalCarriageRole.ENTRY)) {
                assertTrue(cell[0] >= corridorLength,
                    "entry-local x=" + cell[0] + " is inside the entry corridor (length "
                        + corridorLength + ")");
            }
            for (int[] cell : PortalCentreWall.doorwayCellsFromCorridor(dims, PortalCorridorKind.LONG, PortalCarriageRole.EXIT)) {
                assertTrue(cell[0] < 0,
                    "exit-local x=" + cell[0] + " is inside the exit corridor");
            }
        }
    }

    /**
     * The same two properties under {@link PortalCorridorKind#SHORT}, where the "wall" is the whole
     * cart.
     *
     * <p>Worth its own test because the branch that stamps a short pair's cart writes a
     * <b>sealed</b> carriage rather than a wall with a column already in it, so the opening is cut
     * back out afterwards ({@code PortalCarriageBuilder.openSeveredColumn}) — and it can only be cut
     * in the right place if these cells still line up with both corridors. A severed short pair that
     * got this wrong would be three carriages of dead end, which is the outcome the whole class
     * exists to prevent.</p>
     */
    @Test
    @DisplayName("under SHORT the column runs the length of the cart, and both corridors still agree")
    void shortKind_opensTheWholeCart() {
        for (CarriageDims dims : new CarriageDims[] {DEFAULT_DIMS, EVEN_DIMS}) {
            PortalCorridorKind k = PortalCorridorKind.SHORT;

            assertEquals(0, PortalCentreWall.minX(dims, k));
            assertEquals(dims.length(), PortalCentreWall.maxXExclusive(dims, k));
            assertEquals(dims.length() * 2, PortalCentreWall.doorwayCells(dims, k).length,
                "two cells per cart column at length " + dims.length());

            // A short corridor's origin is its slot's, so the shifts are a plain carriage length
            // either way — and the two must still name the same blocks of the group.
            Set<String> fromEntry = inGroupFrame(
                PortalCentreWall.doorwayCellsFromCorridor(dims, k, PortalCarriageRole.ENTRY), 0);
            Set<String> fromExit = inGroupFrame(
                PortalCentreWall.doorwayCellsFromCorridor(dims, k, PortalCarriageRole.EXIT),
                2 * dims.length());
            Set<String> fromMiddle =
                inGroupFrame(PortalCentreWall.doorwayCells(dims, k), dims.length());

            assertEquals(fromMiddle, fromEntry, "entry frame at length " + dims.length());
            assertEquals(fromMiddle, fromExit, "exit frame at length " + dims.length());

            // Still outside both corridors, so opening it mirrors into neither twin.
            for (int[] cell : PortalCentreWall.doorwayCellsFromCorridor(
                    dims, k, PortalCarriageRole.ENTRY)) {
                assertTrue(cell[0] >= dims.length(), "entry-local x=" + cell[0] + " is inside it");
            }
            for (int[] cell : PortalCentreWall.doorwayCellsFromCorridor(
                    dims, k, PortalCarriageRole.EXIT)) {
                assertTrue(cell[0] < 0, "exit-local x=" + cell[0] + " is inside it");
            }
        }
    }

    /** Cells shifted out of a corridor's local frame and into the group's, as comparable keys. */
    private static Set<String> inGroupFrame(int[][] cells, int originInGroup) {
        Set<String> out = new HashSet<>();
        for (int[] cell : cells) {
            out.add((cell[0] + originInGroup) + "," + cell[1] + "," + cell[2]);
        }
        return out;
    }

    /**
     * The same agreement, phrased the way the live code now reaches the wall: from each corridor's
     * WORLD origin as {@code PortalCorridorSize.corridorOriginX} places it. This is the invariant
     * behind the walk-through fallback — an exit corridor opening the plate must hit the very cells
     * an entry corridor would, or one of them opens a hole somewhere else in the group.
     */
    @Test
    @DisplayName("from either corridor's world origin the column lands on the same world cells")
    void worldOriginsAgreeForBothRolesAndKinds() {
        double groupMinX = 5000.25;
        int padLen = 4;
        for (CarriageDims dims : new CarriageDims[] {DEFAULT_DIMS, EVEN_DIMS}) {
            for (PortalCorridorKind k : PortalCorridorKind.values()) {
                double entryOrigin = PortalCorridorSize.corridorOriginX(groupMinX, padLen,
                    PortalCarriageSelection.SLOT_ENTRY, dims, PortalCarriageRole.ENTRY, k);
                double exitOrigin = PortalCorridorSize.corridorOriginX(groupMinX, padLen,
                    PortalCarriageSelection.SLOT_EXIT, dims, PortalCarriageRole.EXIT, k);

                Set<String> fromEntry = new HashSet<>();
                for (int[] c : PortalCentreWall.doorwayCellsFromCorridor(dims, k, PortalCarriageRole.ENTRY)) {
                    fromEntry.add(Math.floor(entryOrigin + c[0]) + "," + c[1] + "," + c[2]);
                }
                Set<String> fromExit = new HashSet<>();
                for (int[] c : PortalCentreWall.doorwayCellsFromCorridor(dims, k, PortalCarriageRole.EXIT)) {
                    fromExit.add(Math.floor(exitOrigin + c[0]) + "," + c[1] + "," + c[2]);
                }
                assertEquals(fromEntry, fromExit, "length " + dims.length() + " " + k);
                assertFalse(fromEntry.isEmpty());
            }
        }
    }
}
