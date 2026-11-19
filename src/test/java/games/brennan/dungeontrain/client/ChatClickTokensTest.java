package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.chat.ChatClick;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One-time chat button tokens: once, only the right one, only while fresh, never in their first moment. */
final class ChatClickTokensTest {

    private final AtomicLong now = new AtomicLong(1_000_000);

    @BeforeEach
    void setUp() {
        ChatClickTokens.useClock(now::get);
    }

    @AfterEach
    void tearDown() {
        ChatClickTokens.useClock(null);
    }

    private String mintAndAge(String scope, long id) {
        String t = ChatClickTokens.mint(scope, id);
        now.addAndGet(ChatClickTokens.MIN_AGE_MS);
        return t;
    }

    @Test
    @DisplayName("a token fits a chat click segment and is not guessable from the id")
    void shape() {
        String t = ChatClickTokens.mint("s", 1);
        assertEquals(22, t.length());
        assertDoesNotThrow(() -> ChatClick.value("a", "go", "1", t));
        assertNotEquals(t, ChatClickTokens.mint("s", 2));
    }

    @Test
    @DisplayName("the right token works once")
    void once() {
        String t = mintAndAge("s", 7);
        assertTrue(ChatClickTokens.consume("s", 7, t));
        assertFalse(ChatClickTokens.consume("s", 7, t));
    }

    @Test
    @DisplayName("a wrong or missing token fails and uses the real one up")
    void wrong() {
        String t = mintAndAge("s", 7);
        assertFalse(ChatClickTokens.consume("s", 7, "A".repeat(22)));
        assertFalse(ChatClickTokens.consume("s", 7, t));
        mintAndAge("s", 8);
        assertFalse(ChatClickTokens.consume("s", 8, null));
        assertFalse(ChatClickTokens.consume("s", 9, t), "never minted");
    }

    @Test
    @DisplayName("a token belongs to its scope and id")
    void scoped() {
        String t = mintAndAge("s", 7);
        assertFalse(ChatClickTokens.consume("other", 7, t));
        assertFalse(ChatClickTokens.consume("s", 8, t));
    }

    @Test
    @DisplayName("too young (a double click landing on the new line) and too old both fail")
    void age() {
        String young = ChatClickTokens.mint("s", 1);
        now.addAndGet(ChatClickTokens.MIN_AGE_MS - 1);
        assertFalse(ChatClickTokens.consume("s", 1, young));

        String old = ChatClickTokens.mint("s", 2);
        now.addAndGet(ChatClickTokens.TTL_MS + 1);
        assertFalse(ChatClickTokens.consume("s", 2, old));
    }

    @Test
    @DisplayName("minting again replaces the earlier token")
    void replaced() {
        String first = ChatClickTokens.mint("s", 1);
        String second = mintAndAge("s", 1);
        assertFalse(ChatClickTokens.consume("s", 1, first));
        assertFalse(ChatClickTokens.consume("s", 1, second), "the wrong guess used it up");

        String third = ChatClickTokens.mint("s", 1);
        String fourth = mintAndAge("s", 1);
        assertTrue(ChatClickTokens.consume("s", 1, fourth));
        assertNotEquals(third, fourth);
    }

    @Test
    @DisplayName("clear forgets one scope only; the map stays bounded")
    void clearAndBound() {
        String a = ChatClickTokens.mint("a", 1);
        String b = ChatClickTokens.mint("b", 1);
        now.addAndGet(ChatClickTokens.MIN_AGE_MS);
        ChatClickTokens.clear("a");
        assertFalse(ChatClickTokens.consume("a", 1, a));
        assertTrue(ChatClickTokens.consume("b", 1, b));

        for (int i = 0; i < ChatClickTokens.MAX * 2; i++) ChatClickTokens.mint("s", i);
        assertEquals(ChatClickTokens.MAX, ChatClickTokens.size());
    }
}
