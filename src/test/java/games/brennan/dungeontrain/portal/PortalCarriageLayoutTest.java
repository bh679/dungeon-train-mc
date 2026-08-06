package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math tests for {@link PortalCarriageLayout} — the in-carriage corridor's shape.
 *
 * <p>Mostly here for the walkability invariant, which is the one property of this layout that a
 * player can be physically blocked by and that nothing else checks.</p>
 */
final class PortalCarriageLayoutTest {

    private static PortalCarriageLayout layout(int length) {
        return new PortalCarriageLayout(length, 7, 7);
    }

    /**
     * The bug this pins: a baffle directly behind a door plane blocks the doorway's own column, so
     * the only route from the doorway to the open side is a diagonal step between two solid corners.
     * Minecraft refuses that — the blocks touch at an edge, leaving a zero-width gap a 0.6-wide
     * player cannot pass — which made the corridor's far door unreachable and the pocket room
     * unenterable on foot. A clear column between door and baffle keeps every step orthogonal.
     */
    @Test
    @DisplayName("each baffle leaves a clear column between itself and its door, or the corridor is unwalkable")
    void bafflesLeaveRoomToStepAside() {
        for (int length : new int[] {9, 12, 16, 32}) {
            PortalCarriageLayout l = layout(length);
            assertTrue(l.nearBaffleX() - l.nearDoorX() >= 2,
                "near baffle is flush against its door at length " + length);
            assertTrue(l.farDoorX() - l.farBaffleX() >= 2,
                "far baffle is flush against its door at length " + length);
        }
    }

    @Test
    @DisplayName("the crossing zone lies between the baffles and contains the midpoint")
    void crossingZoneHoldsTheMidpoint() {
        PortalCarriageLayout l = layout(9);
        assertTrue(l.isCrossingZone((int) Math.floor(l.midX())),
            "the midpoint column must be in the lit crossing zone");
        assertTrue(l.isCrossingZone(l.nearBaffleX() + 1));
        assertTrue(l.isCrossingZone(l.farBaffleX() - 1));

        // The baffles themselves and everything outside them are not the crossing zone.
        assertTrue(!l.isCrossingZone(l.nearBaffleX()));
        assertTrue(!l.isCrossingZone(l.farBaffleX()));
        assertTrue(!l.isCrossingZone(l.nearDoorX()));
        assertTrue(!l.isCrossingZone(l.farDoorX()));
    }

    /**
     * The doorway sits on the walkway centre, and the baffles block up to and including that column
     * — so no straight run down the doorway's own line survives to the far end.
     */
    @Test
    @DisplayName("both baffles interrupt the doorway's column, so neither door is visible from the crossing zone")
    void bafflesBreakTheSightLine() {
        PortalCarriageLayout l = layout(9);
        assertEquals(l.doorZ(), l.baffleZ());
        assertTrue(l.detourZ() > l.baffleZ(), "the detour must be on the open side of the baffle");
        assertTrue(l.detourZ() <= l.interiorMaxZ(), "the detour must stay inside the corridor");
    }

    @Test
    @DisplayName("copyForLocalX splits at the midpoint, with the tie going to the near half")
    void copySplit() {
        PortalCarriageLayout l = layout(9);
        assertEquals(PortalGeometry.COPY_NEAR, l.copyForLocalX(l.midX() - 0.1));
        assertEquals(PortalGeometry.COPY_NEAR, l.copyForLocalX(l.midX()));
        assertEquals(PortalGeometry.COPY_FAR, l.copyForLocalX(l.midX() + 0.1));
    }
}
