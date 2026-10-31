package games.brennan.dungeontrain.client.perf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.perf.LagReportPrompt.JOIN_GRACE_MS;
import static games.brennan.dungeontrain.client.perf.LagReportPrompt.NEVER;
import static games.brennan.dungeontrain.client.perf.LagReportPrompt.PROMPT_COOLDOWN_MS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage of when {@link LagReportPrompt} offers to send the log: in a world, past the
 * join grace, outside the cooldown, and never after a [No].
 */
final class LagReportPromptTest {

    private static final long JOINED = 1_000_000L;
    private static final long LATE = JOINED + JOIN_GRACE_MS + 60_000;

    @Test
    @DisplayName("A freeze well into a world, first of the session, is offered")
    void firstFreezeInWorldPrompts() {
        assertTrue(LagReportPrompt.shouldPrompt(true, LATE, JOINED, NEVER, false, LATE + 5_000));
    }

    @Test
    @DisplayName("A freeze that began outside a world (menus, loading) is not offered")
    void notInWorldNeverPrompts() {
        assertFalse(LagReportPrompt.shouldPrompt(false, LATE, JOINED, NEVER, false, LATE + 5_000));
        assertFalse(LagReportPrompt.shouldPrompt(true, LATE, NEVER, NEVER, false, LATE + 5_000));
    }

    @Test
    @DisplayName("A freeze in the join grace is not offered; one at its end is")
    void joinGrace() {
        assertFalse(LagReportPrompt.shouldPrompt(true, JOINED + JOIN_GRACE_MS - 1, JOINED, NEVER, false, LATE));
        assertTrue(LagReportPrompt.shouldPrompt(true, JOINED + JOIN_GRACE_MS, JOINED, NEVER, false, LATE));
    }

    @Test
    @DisplayName("Inside the cooldown after a prompt nothing is asked; after it, asked again")
    void cooldown() {
        long asked = LATE;
        assertFalse(LagReportPrompt.shouldPrompt(true, LATE, JOINED, asked, false, asked + PROMPT_COOLDOWN_MS - 1));
        assertTrue(LagReportPrompt.shouldPrompt(true, LATE, JOINED, asked, false, asked + PROMPT_COOLDOWN_MS));
    }

    @Test
    @DisplayName("After a [No] the session is never asked again")
    void declinedSilences() {
        assertFalse(LagReportPrompt.shouldPrompt(true, LATE, JOINED, NEVER, true, LATE + PROMPT_COOLDOWN_MS * 10));
    }
}
