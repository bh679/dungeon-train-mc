package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveCloseCallEventsTest {

    @Test
    @DisplayName("one close call per player every 30 minutes")
    void cooldown() {
        long gap = LiveCloseCallEvents.COOLDOWN_MS;
        assertFalse(LiveCloseCallEvents.coolingDown(null, 1000));
        assertTrue(LiveCloseCallEvents.coolingDown(1000L, 1000 + gap - 1));
        assertFalse(LiveCloseCallEvents.coolingDown(1000L, 1000 + gap));
        assertFalse(LiveCloseCallEvents.coolingDown(5000L, 1000), "a clock that went backwards never blocks");
    }
}
