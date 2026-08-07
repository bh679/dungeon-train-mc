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

        assertEquals(4, PortalCorridorSize.overrun(d));
        assertEquals(13, PortalCorridorSize.corridorLength(d));
        assertEquals(1, PortalCorridorSize.centreWallWidth(d));

        // The picture in PortalCorridorSize's javadoc: a group of three is 27 blocks, the entry runs
        // 0..12, the exit 14..26, and x=13 is the wall between them.
        int group = 3 * d.length();
        int entryEnd = PortalCorridorSize.corridorLength(d) - 1;
        int exitStart = group - PortalCorridorSize.corridorLength(d);
        assertEquals(12, entryEnd);
        assertEquals(14, exitStart);
        assertEquals(1, exitStart - entryEnd - 1);
    }

    @Test
    @DisplayName("The exit corridor is pulled back by the overrun; the entry keeps its slot origin")
    void originOffset_movesOnlyTheExit() {
        CarriageDims d = CarriageDims.DEFAULT;
        assertEquals(0, PortalCorridorSize.originOffsetX(PortalCarriageRole.ENTRY, d));
        assertEquals(-4, PortalCorridorSize.originOffsetX(PortalCarriageRole.EXIT, d));

        // Pulled back by exactly enough that the corridor still ends flush with its slot: the far
        // door is the way back onto the train and cannot move.
        int slot2Origin = 2 * d.length();
        int exitOrigin = slot2Origin + PortalCorridorSize.originOffsetX(PortalCarriageRole.EXIT, d);
        assertEquals(3 * d.length(), exitOrigin + PortalCorridorSize.corridorLength(d));
    }

    @Test
    @DisplayName("Across every legal carriage length the two corridors always leave a wall standing")
    void everyLength_leavesAWallAndFitsTheGroup() {
        for (int length = CarriageDims.MIN_LENGTH; length <= CarriageDims.MAX_LENGTH; length++) {
            CarriageDims d = dims(length);
            int corridor = PortalCorridorSize.corridorLength(d);
            int wall = PortalCorridorSize.centreWallWidth(d);

            assertTrue(wall >= 1, "length " + length + " must leave at least one block of wall");
            assertEquals(length, 2 * PortalCorridorSize.overrun(d) + wall,
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
            CarriageDims corridor = PortalCorridorSize.corridorDims(d);
            assertEquals(PortalCorridorSize.corridorLength(d), corridor.length());
            assertEquals(d.width(), corridor.width());
            assertEquals(d.height(), corridor.height());
        }
    }

    @Test
    @DisplayName("At MAX_LENGTH the corridor stops growing and the pair keeps its old shape")
    void atMaxLength_theCorridorIsJustTheCarriage() {
        CarriageDims d = dims(CarriageDims.MAX_LENGTH);

        assertEquals(0, PortalCorridorSize.overrun(d));
        assertEquals(CarriageDims.MAX_LENGTH, PortalCorridorSize.corridorLength(d));
        assertEquals(CarriageDims.MAX_LENGTH, PortalCorridorSize.centreWallWidth(d));
        assertEquals(0, PortalCorridorSize.originOffsetX(PortalCarriageRole.EXIT, d));
    }

    @Test
    @DisplayName("An even carriage length leaves a two-block wall rather than half of one")
    void evenLength_leavesTwo() {
        CarriageDims d = dims(10);
        assertEquals(4, PortalCorridorSize.overrun(d));
        assertEquals(14, PortalCorridorSize.corridorLength(d));
        assertEquals(2, PortalCorridorSize.centreWallWidth(d));
    }
}
