package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one rule that decides how far a corridor grows into the cart between a portal's pair.
 *
 * <p>Everything else in the portal system reads it — the layout, the twin spacing, the room origin,
 * the placement origin, the mask — so a change here moves all of them together. These tests pin the
 * two properties that make the shape safe: the two corridors never meet, and the corridor never
 * asks for a box {@link CarriageDims} would refuse to build.</p>
 */
class PortalCorridorSizeTest {

    private static CarriageDims dims(int length) {
        return new CarriageDims(length, CarriageDims.DEFAULT_WIDTH, CarriageDims.DEFAULT_HEIGHT);
    }

    @Test
    @DisplayName("At the shipped 9-block carriage a corridor is 13 long, leaving one block of wall")
    void defaultDims_give13AndAOneBlockWall() {
        CarriageDims d = CarriageDims.DEFAULT;

        assertEquals(4, PortalCorridorSize.overrun(d, PortalCorridorKind.LONG));
        assertEquals(13, PortalCorridorSize.corridorLength(d, PortalCorridorKind.LONG));
        assertEquals(1, PortalCorridorSize.centreWallWidth(d, PortalCorridorKind.LONG));

        // The picture in PortalCorridorSize's javadoc: a group of three is 27 blocks, the entry runs
        // 0..12, the exit 14..26, and x=13 is the wall between them.
        int group = 3 * d.length();
        int entryEnd = PortalCorridorSize.corridorLength(d, PortalCorridorKind.LONG) - 1;
        int exitStart = group - PortalCorridorSize.corridorLength(d, PortalCorridorKind.LONG);
        assertEquals(12, entryEnd);
        assertEquals(14, exitStart);
        assertEquals(1, exitStart - entryEnd - 1);
    }

    @Test
    @DisplayName("The exit corridor is pulled back by the overrun; the entry keeps its slot origin")
    void originOffset_movesOnlyTheExit() {
        CarriageDims d = CarriageDims.DEFAULT;
        assertEquals(0, PortalCorridorSize.originOffsetX(PortalCarriageRole.ENTRY, d, PortalCorridorKind.LONG));
        assertEquals(-4, PortalCorridorSize.originOffsetX(PortalCarriageRole.EXIT, d, PortalCorridorKind.LONG));

        // Pulled back by exactly enough that the corridor still ends flush with its slot: the far
        // door is the way back onto the train and cannot move.
        int slot2Origin = 2 * d.length();
        int exitOrigin = slot2Origin + PortalCorridorSize.originOffsetX(PortalCarriageRole.EXIT, d, PortalCorridorKind.LONG);
        assertEquals(3 * d.length(), exitOrigin + PortalCorridorSize.corridorLength(d, PortalCorridorKind.LONG));
    }

    @Test
    @DisplayName("Across every legal carriage length the two corridors always leave a wall standing")
    void everyLength_leavesAWallAndFitsTheGroup() {
        for (int length = CarriageDims.MIN_LENGTH; length <= CarriageDims.MAX_LENGTH; length++) {
            CarriageDims d = dims(length);
            int corridor = PortalCorridorSize.corridorLength(d, PortalCorridorKind.LONG);
            int wall = PortalCorridorSize.centreWallWidth(d, PortalCorridorKind.LONG);

            assertTrue(wall >= 1, "length " + length + " must leave at least one block of wall");
            assertEquals(length, 2 * PortalCorridorSize.overrun(d, PortalCorridorKind.LONG) + wall,
                "length " + length + ": the two overruns plus the wall are exactly the cart");
            assertEquals(3 * length, 2 * corridor + wall,
                "length " + length + ": the pair plus the wall fill the group exactly");
            assertTrue(corridor >= length, "a corridor is never shorter than its slot");
        }
    }

    @Test
    @DisplayName("A corridor never asks for a box CarriageDims would refuse — MAX_LENGTH caps it")
    void neverExceedsMaxLength() {
        for (int length = CarriageDims.MIN_LENGTH; length <= CarriageDims.MAX_LENGTH; length++) {
            CarriageDims d = dims(length);
            // Throws if the corridor length fell outside [MIN_LENGTH, MAX_LENGTH] — which is the
            // whole point of the cap in overrun().
            CarriageDims corridor = PortalCorridorSize.corridorDims(d, PortalCorridorKind.LONG);
            assertEquals(PortalCorridorSize.corridorLength(d, PortalCorridorKind.LONG), corridor.length());
            assertEquals(d.width(), corridor.width());
            assertEquals(d.height(), corridor.height());
        }
    }

    @Test
    @DisplayName("At MAX_LENGTH the corridor stops growing and the pair keeps its old shape")
    void atMaxLength_theCorridorIsJustTheCarriage() {
        CarriageDims d = dims(CarriageDims.MAX_LENGTH);

        assertEquals(0, PortalCorridorSize.overrun(d, PortalCorridorKind.LONG));
        assertEquals(CarriageDims.MAX_LENGTH, PortalCorridorSize.corridorLength(d, PortalCorridorKind.LONG));
        assertEquals(CarriageDims.MAX_LENGTH, PortalCorridorSize.centreWallWidth(d, PortalCorridorKind.LONG));
        assertEquals(0, PortalCorridorSize.originOffsetX(PortalCarriageRole.EXIT, d, PortalCorridorKind.LONG));
    }

    @Test
    @DisplayName("An even carriage length leaves a two-block wall rather than half of one")
    void evenLength_leavesTwo() {
        CarriageDims d = dims(10);
        assertEquals(4, PortalCorridorSize.overrun(d, PortalCorridorKind.LONG));
        assertEquals(14, PortalCorridorSize.corridorLength(d, PortalCorridorKind.LONG));
        assertEquals(2, PortalCorridorSize.centreWallWidth(d, PortalCorridorKind.LONG));
    }

    @Test
    @DisplayName("A SHORT corridor is exactly its carriage slot, at every legal length")
    void shortKind_isExactlyTheSlot() {
        for (int length = CarriageDims.MIN_LENGTH; length <= CarriageDims.MAX_LENGTH; length++) {
            CarriageDims d = dims(length);
            PortalCorridorKind k = PortalCorridorKind.SHORT;

            assertEquals(0, PortalCorridorSize.overrun(d, k),
                "length " + length + ": a short corridor reaches into no part of the cart");
            assertEquals(length, PortalCorridorSize.corridorLength(d, k));
            assertEquals(d, PortalCorridorSize.corridorDims(d, k),
                "length " + length + ": a short corridor's box IS the carriage's — which is what "
                    + "lets CarriagePlacer.variantDims leave portal_short alone");
            // Neither end moves: with no overrun there is nothing to pull the exit back by.
            assertEquals(0, PortalCorridorSize.originOffsetX(PortalCarriageRole.ENTRY, d, k));
            assertEquals(0, PortalCorridorSize.originOffsetX(PortalCarriageRole.EXIT, d, k));
        }
    }

    @Test
    @DisplayName("Under SHORT the whole cart survives as the wall between the pair")
    void shortKind_leavesTheWholeCartStanding() {
        CarriageDims d = CarriageDims.DEFAULT;
        PortalCorridorKind k = PortalCorridorKind.SHORT;

        // Not one block but nine: the corridors stop at their slot boundaries, so what stands
        // between them is a whole carriage. PortalCentreWall's arithmetic follows this without a
        // special case, which is what makes a severed short pair a walk-through rather than a
        // one-block hole in a sealed cart.
        assertEquals(d.length(), PortalCorridorSize.centreWallWidth(d, k));
        assertEquals(0, PortalCentreWall.minX(d, k));
        assertEquals(d.length(), PortalCentreWall.maxXExclusive(d, k));

        // The group still adds up exactly, as it does for LONG.
        assertEquals(3 * d.length(),
            2 * PortalCorridorSize.corridorLength(d, k) + PortalCorridorSize.centreWallWidth(d, k));
    }

    /**
     * The tick handler anchors a pair's structure on the ENTRY corridor's origin from BOTH
     * corridors, so the two origins have to be related by a number both can compute: two carriage
     * lengths, less the exit's pull-back into the cart.
     */
    @Test
    @DisplayName("The exit corridor's origin is the entry's plus two lengths, less the overrun")
    void exitOriginRelatesToEntryOriginByTheOverrun() {
        double groupMinX = 1234.56;
        int padLen = 4;
        for (int length : new int[] {9, 10, 12}) {
            CarriageDims d = dims(length);
            for (PortalCorridorKind k : PortalCorridorKind.values()) {
                double entry = PortalCorridorSize.corridorOriginX(groupMinX, padLen,
                    PortalCarriageSelection.SLOT_ENTRY, d, PortalCarriageRole.ENTRY, k);
                double exit = PortalCorridorSize.corridorOriginX(groupMinX, padLen,
                    PortalCarriageSelection.SLOT_EXIT, d, PortalCarriageRole.EXIT, k);
                assertEquals(groupMinX + padLen, entry, 1e-9, "entry keeps its slot at " + length + " " + k);
                assertEquals(2 * length - PortalCorridorSize.overrun(d, k), exit - entry, 1e-9,
                    "exit offset at length " + length + " " + k);
            }
            assertEquals(2 * length,
                PortalCorridorSize.corridorOriginX(0, 0, PortalCarriageSelection.SLOT_EXIT, d,
                    PortalCarriageRole.EXIT, PortalCorridorKind.SHORT), 1e-9,
                "SHORT pulls nothing back at length " + length);
        }
    }
}
