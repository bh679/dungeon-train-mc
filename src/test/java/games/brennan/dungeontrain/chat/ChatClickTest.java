package games.brennan.dungeontrain.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link ChatClick}: the value a button carries, and reading it back. */
class ChatClickTest {

    @Test
    @DisplayName("value() joins segments under the DT prefix with no leading slash")
    void valueShape() {
        assertEquals("dungeontrain:live-gift/accept/abc123", ChatClick.value("live-gift", "accept", "abc123"));
        assertEquals("dungeontrain:consent", ChatClick.value("consent"));
    }

    @Test
    @DisplayName("parse() returns the segments of a DT value and null for anything else")
    void parseRoundTrip() {
        assertEquals(List.of("live-gift", "accept", "abc123"), ChatClick.parse(ChatClick.value("live-gift", "accept", "abc123")));
        assertEquals(List.of("consent"), ChatClick.parse("dungeontrain:consent"));
        assertEquals(List.of(), ChatClick.parse("dungeontrain:"));
        assertNull(ChatClick.parse("/give @s stone"));
        assertNull(ChatClick.parse("/dungeontrain-live-takeover abc"));
        assertNull(ChatClick.parse(null));
    }

    @Test
    @DisplayName("A segment may not be empty or carry the separator or a space")
    void badSegments() {
        assertThrows(IllegalArgumentException.class, ChatClick::value);
        assertThrows(IllegalArgumentException.class, () -> ChatClick.value("a/b"));
        assertThrows(IllegalArgumentException.class, () -> ChatClick.value("a b"));
        assertThrows(IllegalArgumentException.class, () -> ChatClick.value("a", ""));
    }
}
