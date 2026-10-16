package games.brennan.dungeontrain.client.chat;

import games.brennan.dungeontrain.discord.chat.ChatReaction;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatReactionChipsTest {

    @Test
    @DisplayName("no reactions: the line is untouched")
    void none() {
        Component base = Component.literal("hi");
        assertSame(base, ChatReactionTracker.withChips(base, List.of()));
    }

    @Test
    @DisplayName("each emoji becomes a [emoji count] chip after the text")
    void chips() {
        Component out = ChatReactionTracker.withChips(Component.literal("gg"), List.of(
            new ChatReaction("🔥", "", false, 2, List.of("Brennan", "Sam")),
            new ChatReaction("👍", "", false, 1, List.of())));
        String s = out.getString();
        assertTrue(s.startsWith("gg ["), s);
        assertTrue(s.endsWith(" 2] [" + s.substring(s.lastIndexOf('[') + 1, s.lastIndexOf(' ')) + " 1]"), s);
    }

    @Test
    @DisplayName("snippets keep whole code points and end with … when cut")
    void snippet() {
        assertEquals("short", ChatReactionTracker.snippet("  short "));
        String cut = ChatReactionTracker.snippet("😀".repeat(30));
        assertEquals(24 * 2 + 1, cut.length());
        assertTrue(cut.endsWith("…"));
    }
}
