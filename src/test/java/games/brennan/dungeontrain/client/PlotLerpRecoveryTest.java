package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.PlotLerpRecovery.HOLD_TICKS;
import static games.brennan.dungeontrain.client.PlotLerpRecovery.Step;
import static games.brennan.dungeontrain.client.PlotLerpRecovery.step;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure-logic coverage for {@link PlotLerpRecovery#step}. */
final class PlotLerpRecoveryTest {

    @Test
    @DisplayName("a parked mob re-attaches as soon as its carriage is back")
    void attachesWhenCarriageReturns() {
        assertEquals(Step.ATTACH, step(false, true, HOLD_TICKS));
        assertEquals(Step.ATTACH, step(false, true, 1));
    }

    @Test
    @DisplayName("it waits while the carriage is missing, then lets go")
    void waitsThenTimesOut() {
        assertEquals(Step.WAIT, step(false, false, HOLD_TICKS));
        assertEquals(Step.WAIT, step(false, false, 2));
        assertEquals(Step.DROP, step(false, false, 1));
    }

    @Test
    @DisplayName("a removed mob, or one Sable already follows again, is dropped")
    void dropsWhenGoneOrFollowed() {
        assertEquals(Step.DROP, step(true, true, HOLD_TICKS));
        assertEquals(Step.DROP, step(true, false, HOLD_TICKS));
    }
}
