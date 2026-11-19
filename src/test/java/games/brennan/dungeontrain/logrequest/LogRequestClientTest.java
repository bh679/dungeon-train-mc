package games.brennan.dungeontrain.logrequest;

import games.brennan.dungeontrain.client.ChatClickTokens;
import games.brennan.dungeontrain.client.ChatClickValues;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The log request's [Send logs] / [No thanks] only answer from the line this client printed. */
final class LogRequestClientTest {

    private final AtomicLong now = new AtomicLong(1_000_000);

    @BeforeEach
    void setUp() {
        ChatClickTokens.useClock(now::get);
    }

    @AfterEach
    void tearDown() {
        ChatClickTokens.useClock(null);
    }

    /** The click's {@code [step, id, token]}, as the handler gets it. */
    private static List<String> args(Component line, String step) {
        List<String> parts = ChatClickValues.step(line, step);
        assertEquals(LogRequestClient.CLICK_ACTION, parts.get(0));
        return parts.subList(1, parts.size());
    }

    @Test
    @DisplayName("both buttons carry the request id and the same fresh token")
    void buttonsCarryToken() {
        Component line = LogRequestClient.promptLine(7, false);
        List<String> send = args(line, "send");
        List<String> decline = args(line, "decline");
        assertEquals(3, send.size());
        assertEquals("7", send.get(1));
        assertEquals(send.get(2), decline.get(2));
        assertEquals(7, LogRequestClient.idOf(send));
    }

    @Test
    @DisplayName("the old shape, without a token, is not a click this handler acts on")
    void oldShapeRefused() {
        assertEquals(-1, LogRequestClient.idOf(List.of("send", "7")));
        assertEquals(-1, LogRequestClient.idOf(List.of("send", "7", "")));
        assertEquals(-1, LogRequestClient.idOf(List.of("send", "0", "x")));
        assertEquals(-1, LogRequestClient.idOf(List.of("send", "seven", "x")));
    }

    @Test
    @DisplayName("the printed token answers the waiting request once")
    void realTokenOnce() {
        String token = args(LogRequestClient.promptLine(7, false), "send").get(2);
        now.addAndGet(ChatClickTokens.MIN_AGE_MS);
        assertTrue(LogRequestClient.clickAllowed(7, 7, token));
        assertFalse(LogRequestClient.clickAllowed(7, 7, token), "used up");
    }

    @Test
    @DisplayName("a forged click (the server knows the id) sends nothing")
    void forgedRefused() {
        String token = args(LogRequestClient.promptLine(7, false), "send").get(2);
        now.addAndGet(ChatClickTokens.MIN_AGE_MS);
        assertFalse(LogRequestClient.clickAllowed(7, 7, "made-up"));
        assertFalse(LogRequestClient.clickAllowed(7, 7, null));
        assertFalse(LogRequestClient.clickAllowed(7, 7, token), "a guess costs the real token");
    }

    @Test
    @DisplayName("a token only answers the request still waiting")
    void onlyThePendingRequest() {
        String token = args(LogRequestClient.promptLine(7, false), "send").get(2);
        now.addAndGet(ChatClickTokens.MIN_AGE_MS);
        assertFalse(LogRequestClient.clickAllowed(7, -1, token), "nothing waiting");
        assertFalse(LogRequestClient.clickAllowed(7, 8, token), "a newer request");
    }

    @Test
    @DisplayName("the buttons work for the server's whole answer window, not just two minutes")
    void lastsTheAnswerWindow() {
        String token = args(LogRequestClient.promptLine(7, true), "send").get(2);
        now.addAndGet(LogRequestManager.ANSWER_WINDOW_MS - 1);
        assertTrue(LogRequestClient.clickAllowed(7, 7, token));

        String late = args(LogRequestClient.promptLine(8, true), "send").get(2);
        now.addAndGet(LogRequestManager.ANSWER_WINDOW_MS + 1);
        assertFalse(LogRequestClient.clickAllowed(8, 8, late));
    }
}
