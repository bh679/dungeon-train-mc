package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The drift a twin tolerates before it is re-laid. Pure arithmetic, and the thing it must never do is
 * grow without bound: a twin that trails too far behind its carriage is a swap into chunks the client
 * has not got, which is a dead-end carriage rather than a flash.
 */
final class PortalTwinDriftTest {

    @Test
    @DisplayName("an empty corridor tolerates exactly what it always did")
    void unoccupiedIsUnchanged() {
        assertEquals(PortalTwinDrift.BASE, PortalTwinDrift.allowance(false, 12), 1e-9);
        assertEquals(PortalTwinDrift.BASE, PortalTwinDrift.allowance(false, 32), 1e-9);
        assertEquals(PortalTwinDrift.BASE, PortalTwinDrift.allowance(false, 2), 1e-9);
    }

    @Test
    @DisplayName("a corridor with somebody in it spends half the view distance, and no more")
    void occupiedSpendsHalfTheViewDistance() {
        // 8 chunks of view = 128 blocks; half of that keeps the twin well inside the columns the
        // client already has.
        assertEquals(64.0, PortalTwinDrift.allowance(true, 8), 1e-9);
        assertEquals(PortalTwinDrift.OCCUPIED_CAP, PortalTwinDrift.allowance(true, 12), 1e-9);
        assertEquals(PortalTwinDrift.OCCUPIED_CAP, PortalTwinDrift.allowance(true, 32),
            1e-9, "a very long render distance is capped rather than followed");
    }

    @Test
    @DisplayName("a short render distance never buys less than the old limit")
    void occupiedNeverGoesBelowBase() {
        for (int viewDistance : new int[]{0, 2, 3, 4}) {
            assertTrue(PortalTwinDrift.allowance(true, viewDistance) >= PortalTwinDrift.BASE,
                "view distance " + viewDistance);
        }
        // A nonsense value from a server that has not settled yet falls back rather than misbehaving.
        assertEquals(PortalTwinDrift.BASE, PortalTwinDrift.allowance(true, -8), 1e-9);
    }

    @Test
    @DisplayName("an occupied corridor still relocates eventually — the limit is raised, not removed")
    void occupiedStillRelocates() {
        assertTrue(PortalTwinDrift.allowance(true, 32) < Double.MAX_VALUE);
        assertEquals(PortalTwinDrift.OCCUPIED_CAP, PortalTwinDrift.allowance(true, 64), 1e-9);
    }
}
