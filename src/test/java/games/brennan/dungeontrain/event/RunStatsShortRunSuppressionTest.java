package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link RunStatsEvents#suppressPublicShortRun(int, boolean)}: a run ending under 100
 * carriages stays off the public death feed however it ended, unless an echo did the killing —
 * an echo kill reaches the feed at any length.
 */
final class RunStatsShortRunSuppressionTest {

    @Test
    void shortRunIsSuppressed() {
        assertTrue(RunStatsEvents.suppressPublicShortRun(0, false),  "0 carriages → suppress");
        assertTrue(RunStatsEvents.suppressPublicShortRun(1, false),  "1 carriage → suppress");
        assertTrue(RunStatsEvents.suppressPublicShortRun(99, false), "99 carriages → suppress");
    }

    @Test
    void runAtThresholdPosts() {
        // < 100 is the cutoff, so exactly 100 (and above) still reaches the public feed.
        assertFalse(RunStatsEvents.suppressPublicShortRun(100, false), "100 carriages → post");
        assertFalse(RunStatsEvents.suppressPublicShortRun(250, false), "250 carriages → post");
    }

    @Test
    void echoKillAlwaysPosts() {
        // The cross-player story the feed exists for — never withheld for being short.
        assertFalse(RunStatsEvents.suppressPublicShortRun(0, true),  "echo kill at 0 carriages → post");
        assertFalse(RunStatsEvents.suppressPublicShortRun(99, true), "echo kill at 99 carriages → post");
        assertFalse(RunStatsEvents.suppressPublicShortRun(250, true), "echo kill at 250 carriages → post");
    }
}
