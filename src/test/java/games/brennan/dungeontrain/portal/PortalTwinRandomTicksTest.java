package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PortalTwinRandomTicksTest {

    @Test
    @DisplayName("range is vanilla's: chunk centre strictly within 128 blocks of the player")
    void rangeMatchesVanilla() {
        // Chunk 0's centre is block 8. A player at x=8 is 0 away; at 8+127.9 inside; at 8+128 out.
        assertTrue(PortalTwinRandomTicks.inPlayerRange(0, 0, 8.0, 8.0));
        assertTrue(PortalTwinRandomTicks.inPlayerRange(0, 0, 8.0 + 127.9, 8.0));
        assertFalse(PortalTwinRandomTicks.inPlayerRange(0, 0, 8.0 + 128.0, 8.0));
        // Diagonal: 90.6 on each axis is ~128.1 away, out; 90.5 is ~127.99, in.
        assertFalse(PortalTwinRandomTicks.inPlayerRange(0, 0, 8.0 + 90.6, 8.0 + 90.6));
        assertTrue(PortalTwinRandomTicks.inPlayerRange(0, 0, 8.0 + 90.5, 8.0 + 90.5));
        // Negative chunk coordinates: chunk -1's centre is block -8.
        assertTrue(PortalTwinRandomTicks.inPlayerRange(-1, -1, -8.0, -8.0));
        assertFalse(PortalTwinRandomTicks.inPlayerRange(-9, 0, 8.0, 8.0));
    }

    @Test
    @DisplayName("ticks only where vanilla would, but for the missing player")
    void gatesMirrorVanilla() {
        assertTrue(PortalTwinRandomTicks.shouldTick(true, true, true, false));
        // Vanilla already ticks it (somebody on the train overhead): never twice.
        assertFalse(PortalTwinRandomTicks.shouldTick(true, true, true, true));
        // Any other vanilla gate closed: stays closed.
        assertFalse(PortalTwinRandomTicks.shouldTick(false, true, true, false));
        assertFalse(PortalTwinRandomTicks.shouldTick(true, false, true, false));
        assertFalse(PortalTwinRandomTicks.shouldTick(true, true, false, false));
    }
}
