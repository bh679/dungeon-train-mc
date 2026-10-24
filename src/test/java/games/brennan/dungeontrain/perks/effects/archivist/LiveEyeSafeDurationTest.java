package games.brennan.dungeontrain.perks.effects.archivist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveEyeSafeDurationTest {

    private static final long FIVE_MIN = 300_000L;

    @Test
    void fiveMinutesPerRankSplitHalfAndHalf() {
        assertEquals(0L, LiveEyeSafeDuration.extraMs(0));
        assertEquals(FIVE_MIN, LiveEyeSafeDuration.extraMs(1));
        assertEquals(2 * FIVE_MIN, LiveEyeSafeDuration.extraMs(2));
        assertEquals(FIVE_MIN, LiveEyeSafeDuration.firstWindowExtraMs(2));
        assertEquals(FIVE_MIN, LiveEyeSafeDuration.secondWindowExtraMs(2));
    }

    @Test
    void oddRankRoundsTheOddHalfMinuteIntoTheFirstWindow() {
        long first = LiveEyeSafeDuration.firstWindowExtraMs(1);
        long second = LiveEyeSafeDuration.secondWindowExtraMs(1);
        assertEquals(FIVE_MIN, first + second);
        assertEquals(150_000L, first);
        assertEquals(150_000L, second);
    }

    @Test
    void negativeRankAddsNothingAndHugeRankIsCapped() {
        assertEquals(0L, LiveEyeSafeDuration.extraMs(-3));
        assertEquals(LiveEyeSafeDuration.MAX_RANK_COUNTED * FIVE_MIN, LiveEyeSafeDuration.extraMs(999));
        assertEquals(60L * 60L * 1000L, LiveEyeSafeDuration.extraMs(999), "an hour on top at most");
    }
}
