package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link RunStatsEvents#suppressPublicAbandon(boolean, int)}: an abandoned run reaching
 * fewer than 20 carriages stays off the public death feed; everything else (longer abandons,
 * and all non-abandon deaths regardless of length) still posts.
 */
final class RunStatsAbandonSuppressionTest {

    @Test
    void shortAbandonIsSuppressed() {
        assertTrue(RunStatsEvents.suppressPublicAbandon(true, 0),  "abandon at 0 carriages → suppress");
        assertTrue(RunStatsEvents.suppressPublicAbandon(true, 19), "abandon at 19 carriages → suppress");
    }

    @Test
    void abandonAtThresholdPosts() {
        // < 20 is the cutoff, so exactly 20 (and above) still reaches the public feed.
        assertFalse(RunStatsEvents.suppressPublicAbandon(true, 20), "abandon at 20 carriages → post");
        assertFalse(RunStatsEvents.suppressPublicAbandon(true, 47), "abandon at 47 carriages → post");
    }

    @Test
    void nonAbandonDeathsNeverSuppressed() {
        // A genuine in-run death is always eligible for the public feed, even a very short run.
        assertFalse(RunStatsEvents.suppressPublicAbandon(false, 0),  "normal death at 0 carriages → post");
        assertFalse(RunStatsEvents.suppressPublicAbandon(false, 19), "normal death at 19 carriages → post");
        assertFalse(RunStatsEvents.suppressPublicAbandon(false, 99), "normal death at 99 carriages → post");
    }
}
