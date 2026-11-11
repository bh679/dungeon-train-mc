package games.brennan.dungeontrain.client.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatMessageListLinkTest {

    @Test
    void findsLinkAtTheEnd() {
        assertEquals("https://www.instagram.com/stories/dungeontrain/1/",
                ChatMessageList.trailingLink("Your death at carriage 412 is on our Instagram story! https://www.instagram.com/stories/dungeontrain/1/"));
        assertEquals("https://x.test/a", ChatMessageList.trailingLink("https://x.test/a  "));
    }

    @Test
    void ignoresLinksThatAreNotLast() {
        assertNull(ChatMessageList.trailingLink("see https://x.test/a for more"));
        assertNull(ChatMessageList.trailingLink("plain words"));
        assertNull(ChatMessageList.trailingLink("glued:https://x.test/a"));
        assertNull(ChatMessageList.trailingLink("bare https://"));
        assertNull(ChatMessageList.trailingLink(null));
    }
}
