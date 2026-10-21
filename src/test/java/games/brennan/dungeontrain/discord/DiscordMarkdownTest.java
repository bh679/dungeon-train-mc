package games.brennan.dungeontrain.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure-logic coverage for {@link DiscordMarkdown#escape}. */
class DiscordMarkdownTest {

    @Test
    @DisplayName("plain names pass through unchanged")
    void plainUnchanged() {
        assertEquals("Brennan", DiscordMarkdown.escape("Brennan"));
        assertEquals("Sharpness I, Knockback I (4.5 attack · trim)", DiscordMarkdown.escape("Sharpness I, Knockback I (4.5 attack · trim)"));
    }

    @Test
    @DisplayName("every markdown special is backslash-escaped")
    void escapesSpecials() {
        assertEquals("ashy\\_", DiscordMarkdown.escape("ashy_"));
        assertEquals("\\_\\_x\\_\\_", DiscordMarkdown.escape("__x__"));
        assertEquals("\\*\\~\\`\\|\\>\\#\\[\\]\\\\", DiscordMarkdown.escape("*~`|>#[]\\"));
    }

    @Test
    @DisplayName("null and empty are returned as-is")
    void nullAndEmpty() {
        assertNull(DiscordMarkdown.escape(null));
        assertEquals("", DiscordMarkdown.escape(""));
    }
}
