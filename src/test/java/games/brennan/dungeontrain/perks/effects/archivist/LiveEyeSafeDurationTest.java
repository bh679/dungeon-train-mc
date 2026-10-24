package games.brennan.dungeontrain.perks.effects.archivist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveEyeSafeDurationTest {

    private static final long MIN = 60_000L;

    @Test
    void stepsShrinkFromFiveMinutes() {
        assertEquals(0L, LiveEyeSafeDuration.extraMs(0));
        assertEquals(5 * MIN, LiveEyeSafeDuration.extraMs(1));
        assertEquals(9 * MIN, LiveEyeSafeDuration.extraMs(2));
        assertEquals(12 * MIN, LiveEyeSafeDuration.extraMs(3));
        assertEquals(14 * MIN, LiveEyeSafeDuration.extraMs(4));
        assertEquals(Math.round(15.5 * MIN), LiveEyeSafeDuration.extraMs(5));
    }

    @Test
    void halvesAddUpWithTheOddMillisecondInTheFirstWindow() {
        for (int rank = 0; rank < 30; rank++) {
            long first = LiveEyeSafeDuration.firstWindowExtraMs(rank);
            long second = LiveEyeSafeDuration.secondWindowExtraMs(rank);
            assertEquals(LiveEyeSafeDuration.extraMs(rank), first + second);
            assertTrue(first - second >= 0 && first - second <= 1);
        }
    }

    @Test
    void negativeRankAddsNothingAndThereIsNoCap() {
        assertEquals(0L, LiveEyeSafeDuration.extraMs(-3));
        assertTrue(LiveEyeSafeDuration.extraMs(100) > 30 * MIN, "past half an hour at rank 100");
        assertTrue(LiveEyeSafeDuration.extraMs(101) > LiveEyeSafeDuration.extraMs(100));
        for (int rank = 2; rank < 60; rank++) {
            assertTrue(LiveEyeSafeDuration.stepMinutes(rank) < LiveEyeSafeDuration.stepMinutes(rank - 1));
        }
    }
}
