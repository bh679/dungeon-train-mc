package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalCopyTimingTest {

    @Test
    void anOrdinaryCopyIsNeverLogged() {
        assertFalse(PortalCopyTiming.shouldLog(PortalCopyTiming.LOG_FLOOR_NANOS - 1, true));
    }

    @Test
    void aSlowCopyWaitsForARoutineSlot() {
        assertTrue(PortalCopyTiming.shouldLog(PortalCopyTiming.LOG_FLOOR_NANOS, true));
        assertFalse(PortalCopyTiming.shouldLog(PortalCopyTiming.ALWAYS_LOG_NANOS - 1, false));
    }

    @Test
    void aVerySlowCopyIsLoggedEvenWithoutASlot() {
        assertTrue(PortalCopyTiming.shouldLog(PortalCopyTiming.ALWAYS_LOG_NANOS, false));
    }

    @Test
    void markBeforeBeginIsHarmless() {
        // The stamp path is shared with base rooms and the editor, which never begin a timing.
        PortalCopyTiming.mark(PortalCopyTiming.Phase.CLEAR);
    }
}
