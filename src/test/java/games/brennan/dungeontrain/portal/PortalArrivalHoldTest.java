package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a stopped train stops waiting for the player who just walked out of its room. Every release
 * has to be reachable: a hold that never let go would be a train stopped for good.
 */
class PortalArrivalHoldTest {

    private static final long NOW = 1_000L;
    private static final long UNTIL = NOW + PortalArrivalHold.HOLD_TICKS;

    @Test
    @DisplayName("waits while the player is there and not yet carried")
    void holdsUntilCarried() {
        assertTrue(PortalArrivalHold.keep(false, true, NOW, UNTIL));
        assertTrue(PortalArrivalHold.keep(false, true, UNTIL - 1, UNTIL));
    }

    @Test
    @DisplayName("lets go the moment Sable carries them")
    void releasesWhenCarried() {
        assertFalse(PortalArrivalHold.keep(true, true, NOW, UNTIL));
    }

    @Test
    @DisplayName("lets go when the player is gone or dead")
    void releasesWhenGone() {
        assertFalse(PortalArrivalHold.keep(false, false, NOW, UNTIL));
    }

    @Test
    @DisplayName("lets go when the wait runs out, carried or not")
    void releasesOnTimeout() {
        assertFalse(PortalArrivalHold.keep(false, true, UNTIL, UNTIL));
        assertFalse(PortalArrivalHold.keep(false, true, UNTIL + 50, UNTIL));
    }
}
