package games.brennan.dungeontrain.compat.vista;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.compat.vista.TvChannelCycle.Step.TO_SECOND;
import static games.brennan.dungeontrain.compat.vista.TvChannelCycle.Step.TURN_OFF;
import static games.brennan.dungeontrain.compat.vista.TvChannelCycle.Step.TURN_ON;
import static games.brennan.dungeontrain.compat.vista.TvChannelCycle.next;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class TvChannelCycleTest {

    @Test
    @DisplayName("off → Stream 1 → Stream 2 → off while a second stream is live")
    void fullCycleWithTwoStreams() {
        assertEquals(TURN_ON, next(false, false, false, true));
        assertEquals(TO_SECOND, next(true, true, false, true));
        assertEquals(TURN_OFF, next(true, true, true, true));
    }

    @Test
    @DisplayName("off → Stream 1 → off while only one stream is live, or none")
    void shortCycleWithOneStream() {
        assertEquals(TURN_ON, next(false, false, false, false));
        assertEquals(TURN_OFF, next(true, true, false, false));
        // A TV that spawned on Stream 2 (TV rooms) while no second stream is live: off next, not stuck.
        assertEquals(TURN_OFF, next(true, true, true, false));
    }

    @Test
    @DisplayName("a TV showing something other than the Live Feed just toggles")
    void otherContentToggles() {
        assertEquals(TURN_OFF, next(true, false, false, true));
        assertEquals(TURN_ON, next(false, false, false, true));
    }
}
