package games.brennan.dungeontrain.client.perf;

import org.junit.jupiter.api.DisplayName;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import games.brennan.dungeontrain.client.ChatClickTokens;
import games.brennan.dungeontrain.client.ChatClickValues;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.perf.LagReportPrompt.JOIN_GRACE_MS;
import static games.brennan.dungeontrain.client.perf.LagReportPrompt.NEVER;
import static games.brennan.dungeontrain.client.perf.LagReportPrompt.PROMPT_COOLDOWN_MS;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    // ---- the buttons' token ----------------------------------------------------------------------

    private final AtomicLong now = new AtomicLong(1_000_000);

    @BeforeEach
    void clock() {
        ChatClickTokens.useClock(now::get);
    }

    @AfterEach
    void wallClock() {
        ChatClickTokens.useClock(null);
    }

    private static List<String> args(Component line, String step) {
        List<String> parts = ChatClickValues.step(line, step);
        return parts.subList(1, parts.size());
    }

    @Test
    void buttonsCarryAToken() {
        Component line = LagReportPrompt.promptLine(3);
        List<String> send = args(line, "send");
        assertEquals(3, send.size());
        assertEquals(send.get(2), args(line, "decline").get(2));
        assertEquals(3, LagReportPrompt.idOf(send));
        assertEquals(-1, LagReportPrompt.idOf(List.of("send", "3")), "the old shape");
    }

    @Test
    void onlyThePrintedTokenSendsOnce() {
        String token = args(LagReportPrompt.promptLine(3), "send").get(2);
        now.addAndGet(ChatClickTokens.MIN_AGE_MS);
        assertFalse(LagReportPrompt.clickAllowed(3, 3, "forged"));
        String fresh = args(LagReportPrompt.promptLine(3), "send").get(2);
        now.addAndGet(ChatClickTokens.MIN_AGE_MS);
        assertFalse(LagReportPrompt.clickAllowed(3, 4, fresh), "not the open prompt");
        String again = args(LagReportPrompt.promptLine(3), "send").get(2);
        now.addAndGet(ChatClickTokens.MIN_AGE_MS);
        assertTrue(LagReportPrompt.clickAllowed(3, 3, again));
        assertFalse(LagReportPrompt.clickAllowed(3, 3, again), "used up");
        assertFalse(token.isEmpty());
    }

    @Test
    void outlastsTheDefaultTwoMinutes() {
        String token = args(LagReportPrompt.promptLine(3), "send").get(2);
        now.addAndGet(LagReportPrompt.TOKEN_TTL_MS - 1);
        assertTrue(LagReportPrompt.clickAllowed(3, 3, token));
    }
}
