package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalTwinChunkHoldTest {

    private static final long CAP = PortalTwinChunkHold.DRAIN_HOLD_TICKS;

    @Test
    @DisplayName("copies stay held after the last player leaves, up to the cap")
    void copiesStayHeldUpToTheCap() {
        assertTrue(PortalTwinChunkHold.holdsResidents(false, true, 1L));
        assertTrue(PortalTwinChunkHold.holdsResidents(false, true, CAP));
    }

    @Test
    @DisplayName("a room that never finishes draining stops being held at the cap")
    void neverDrainingRoomStopsAtTheCap() {
        assertFalse(PortalTwinChunkHold.holdsResidents(false, true, CAP + 1));
    }

    @Test
    @DisplayName("a room nobody has been held in since the cap ran out gets no tickets")
    void neverHeldGetsNoTickets() {
        assertFalse(PortalTwinChunkHold.holdsResidents(false, true, Long.MAX_VALUE));
    }

    @Test
    @DisplayName("a held player holds residents with nothing spread: their own room's chunks")
    void heldPlayerHoldsResidents() {
        assertTrue(PortalTwinChunkHold.holdsResidents(true, false, 0L));
    }

    @Test
    @DisplayName("nobody held and nothing left out adds no tickets")
    void nothingToHold() {
        assertFalse(PortalTwinChunkHold.holdsResidents(false, false, 1L));
    }
}
