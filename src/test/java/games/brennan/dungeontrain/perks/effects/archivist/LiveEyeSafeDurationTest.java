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
    void eachWindowGainsHalfTheExtra() {
        for (int rank = 0; rank < 30; rank++) {
            long extra = LiveEyeSafeDuration.extraMs(rank);
            long holder = LiveEyeSafeDuration.holderVoteExtraMs(rank);
            long community = LiveEyeSafeDuration.communityVoteExtraMs(rank);
            // both relay deadlines count from the start of the hold: the takeover deadline moves by the whole extra
            assertEquals(extra, LiveEyeSafeDuration.takeoverExtraMs(rank));
            assertEquals(extra, holder + community);
            assertTrue(community - holder >= 0 && community - holder <= 1);
        }
        assertEquals(Math.round(2.5 * MIN), LiveEyeSafeDuration.holderVoteExtraMs(1));
        assertEquals(Math.round(2.5 * MIN), LiveEyeSafeDuration.communityVoteExtraMs(1));
    }

    @Test
    void negativeRankAddsNothingAndThereIsNoCap() {
        assertEquals(0L, LiveEyeSafeDuration.extraMs(-3));
        assertTrue(LiveEyeSafeDuration.extraMs(1001) > LiveEyeSafeDuration.extraMs(1000));
        for (int rank = 2; rank < 2000; rank++) {
            assertTrue(LiveEyeSafeDuration.stepMinutes(rank) <= LiveEyeSafeDuration.stepMinutes(rank - 1),
                "rank " + rank + " never adds more than the one before");
        }
    }

    @Test
    void thirtyFiveMinutesAtRankTwentyThenFiveSecondsByFortyFiveThenSlower() {
        assertEquals(35 * MIN, LiveEyeSafeDuration.extraMs(20), 1_000L);
        int rank = 20;
        while (LiveEyeSafeDuration.extraMs(rank) < 45 * MIN) rank++;
        assertEquals(5_000L, Math.round(LiveEyeSafeDuration.stepMinutes(rank + 1) * MIN), 1_000L);
        assertTrue(LiveEyeSafeDuration.stepMinutes(500) < LiveEyeSafeDuration.stepMinutes(rank + 1),
            "keeps going down past 45 minutes");
        assertTrue(LiveEyeSafeDuration.stepMinutes(1000) * MIN > 1_000L, "slowly: still a second at rank 1000");
    }
}
