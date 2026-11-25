package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-math tests for {@link PortalTwinDrift}. No NeoForge bootstrap. */
final class PortalTwinDriftTest {

    @Test
    @DisplayName("nobody near, or the smallest render distances, keep the old 24 blocks")
    void floorIsTheOldLimit() {
        assertEquals(24.0, PortalTwinDrift.allowance(0, 16));
        assertEquals(24.0, PortalTwinDrift.allowance(-1, 16));
        assertEquals(24.0, PortalTwinDrift.allowance(2, 16));
        assertEquals(24.0, PortalTwinDrift.allowance(3, 16));
        assertEquals(32.0, PortalTwinDrift.allowance(4, 16));
    }

    @Test
    @DisplayName("the allowance never passes four chunks")
    void capped() {
        assertEquals(64.0, PortalTwinDrift.allowance(32, 16));
        assertEquals(64.0, PortalTwinDrift.allowance(12, 48));
    }

    @Test
    @DisplayName("a wider room is allowed less: its far side has to be loaded too")
    void roomWidthComesOff() {
        assertTrue(PortalTwinDrift.allowance(6, 48) < PortalTwinDrift.allowance(6, 16));
        assertEquals(32.0, PortalTwinDrift.allowance(6, 48));
        assertEquals(24.0, PortalTwinDrift.allowance(4, 48));
    }

    @Test
    @DisplayName("the lead follows the travel direction and stays inside the allowance")
    void leadSignAndMargin() {
        assertEquals(62.0, PortalTwinDrift.lead(64.0, 1));
        assertEquals(-62.0, PortalTwinDrift.lead(64.0, -1));
        assertEquals(0.0, PortalTwinDrift.lead(64.0, 0));
        assertTrue(Math.abs(PortalTwinDrift.lead(24.0, 1)) < 24.0);
    }
}
