package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared budget behind both exhausted-window pool refreshes
 * ({@link NarrativeBookEvents#claimRefreshSlot(long)}).
 *
 * <p>The bug this guards: the post-serve prefetch was ungated, so once a player's window was
 * exhausted every book pickup fired a relay request — measured at roughly one per second against a
 * small pool, each returning nothing new. It bites hardest on non-English players, whose snapshot is
 * language-scoped and therefore small enough that "exhausted" is the normal state.</p>
 *
 * <p>One test method walking a monotonically increasing clock: the throttle's state is static, so
 * this keeps it independent of test ordering without needing a reset hook.</p>
 */
class SharedBookRefreshThrottleTest {

    // Comfortably past any real lastExhaustedRefreshMs (0 at class-init, or a currentTimeMillis from
    // another test), so the first claim below is genuinely the first for this clock.
    private static final long T0 = 4_000_000_000_000L;
    private static final long RETRY_MS = 10_000L;

    @Test
    @DisplayName("one refresh per retry window, however many callers ask")
    void throttlesToOnePerWindow() {
        assertTrue(NarrativeBookEvents.claimRefreshSlot(T0),
            "the first claim in a window must be granted — otherwise an exhausted pool never refreshes");

        // The burst that caused this: pickups arriving a second apart, each wanting a fetch.
        assertFalse(NarrativeBookEvents.claimRefreshSlot(T0 + 1));
        assertFalse(NarrativeBookEvents.claimRefreshSlot(T0 + 1_000));
        assertFalse(NarrativeBookEvents.claimRefreshSlot(T0 + 5_000));

        // Exactly on the boundary is still inside the window — the comparison is <=.
        assertFalse(NarrativeBookEvents.claimRefreshSlot(T0 + RETRY_MS));

        // One millisecond past it, the next fetch is allowed.
        assertTrue(NarrativeBookEvents.claimRefreshSlot(T0 + RETRY_MS + 1));

        // And the window restarts from THAT claim, not from T0 — otherwise a steady stream of
        // pickups would drift back toward one fetch per pickup.
        assertFalse(NarrativeBookEvents.claimRefreshSlot(T0 + RETRY_MS + 2));
        assertFalse(NarrativeBookEvents.claimRefreshSlot(T0 + (2 * RETRY_MS) + 1));
        assertTrue(NarrativeBookEvents.claimRefreshSlot(T0 + (2 * RETRY_MS) + 2));
    }
}
