package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalTwinChunkHoldTest {

    @Test
    @DisplayName("copies stay held after the last player leaves, until they are gone")
    void copiesStayHeldAfterEveryoneLeft() {
        assertTrue(PortalTwinChunkHold.holdsResidents(false, true));
    }

    @Test
    @DisplayName("a held player still holds residents with nothing spread: their own room's chunks")
    void heldPlayerHoldsResidents() {
        assertTrue(PortalTwinChunkHold.holdsResidents(true, false));
    }

    @Test
    @DisplayName("nobody held and nothing left out adds no tickets")
    void nothingToHold() {
        assertFalse(PortalTwinChunkHold.holdsResidents(false, false));
    }
}
