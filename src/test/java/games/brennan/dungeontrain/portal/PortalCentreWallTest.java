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
            int width = PortalCorridorSize.centreWallWidth(dims);
            assertEquals(PortalCentreWall.minX(dims) + width, PortalCentreWall.maxXExclusive(dims),
                "the column spans exactly the wall at length " + dims.length());
            assertEquals(width * 2, PortalCentreWall.doorwayCells(dims).length,
                "two cells per wall column at length " + dims.length());

            // On the same line the corridors put their doors on, or the opening would meet a wall.
            PortalCarriageLayout layout =
                new PortalCarriageLayout(PortalCorridorSize.corridorLength(dims), dims.height(), dims.width());
            assertEquals(layout.doorZ(), PortalCentreWall.doorZ(dims),
                "the column must sit on the corridors' own doorway line");

            // The two cells a door occupies, and not the floor beneath them.
            for (int[] cell : PortalCentreWall.doorwayCells(dims)) {
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
        int x = PortalCentreWall.minX(DEFAULT_DIMS);

        assertFalse(PortalCentreWall.isDoorwayColumn(DEFAULT_DIMS, x, 0, z), "floor");
        assertFalse(PortalCentreWall.isDoorwayColumn(DEFAULT_DIMS, x, 3, z), "above the doorway");
        assertFalse(PortalCentreWall.isDoorwayColumn(DEFAULT_DIMS, x, DEFAULT_DIMS.height() - 1, z),
            "ceiling");
    }

    /** Everything off the walkway line, and everything outside the wall, belongs to somebody else. */
    @Test
    @DisplayName("nothing outside the wall's own X range, or off its Z line, is in the column")
    void onlyTheWallsOwnCells() {
        int z = PortalCentreWall.doorZ(DEFAULT_DIMS);

        assertFalse(PortalCentreWall.isDoorwayColumn(DEFAULT_DIMS,
            PortalCentreWall.minX(DEFAULT_DIMS) - 1, 1, z), "the entry corridor's overrun");
        assertFalse(PortalCentreWall.isDoorwayColumn(DEFAULT_DIMS,
            PortalCentreWall.maxXExclusive(DEFAULT_DIMS), 1, z), "the exit corridor's overrun");

        for (int dz = 0; dz < DEFAULT_DIMS.width(); dz++) {
            if (dz == z) continue;
            assertFalse(PortalCentreWall.isDoorwayColumn(
                DEFAULT_DIMS, PortalCentreWall.minX(DEFAULT_DIMS), 1, dz),
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
                PortalCarriageRole.EXIT, dims);

            Set<String> fromEntry = inGroupFrame(
                PortalCentreWall.doorwayCellsFromCorridor(dims, PortalCarriageRole.ENTRY),
                entryOriginInGroup);
            Set<String> fromExit = inGroupFrame(
                PortalCentreWall.doorwayCellsFromCorridor(dims, PortalCarriageRole.EXIT),
                exitOriginInGroup);
            // The middle slot is the second of the three, so its own origin is one length in.
            Set<String> fromMiddle = inGroupFrame(PortalCentreWall.doorwayCells(dims), dims.length());

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
            int corridorLength = PortalCorridorSize.corridorLength(dims);

            for (int[] cell : PortalCentreWall.doorwayCellsFromCorridor(dims, PortalCarriageRole.ENTRY)) {
                assertTrue(cell[0] >= corridorLength,
                    "entry-local x=" + cell[0] + " is inside the entry corridor (length "
                        + corridorLength + ")");
            }
            for (int[] cell : PortalCentreWall.doorwayCellsFromCorridor(dims, PortalCarriageRole.EXIT)) {
                assertTrue(cell[0] < 0,
                    "exit-local x=" + cell[0] + " is inside the exit corridor");
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
}
