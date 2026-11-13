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

    @Test
    void findsMarkdownLinksInOrder() {
        String text = "Your death is on our [Instagram](https://www.instagram.com/stories/dungeontrain/1/) and "
                + "[Facebook](https://www.facebook.com/1) stories!";
        var spans = ChatMessageList.markdownLinks(text);
        assertEquals(2, spans.size());
        assertEquals("Instagram", spans.get(0).name());
        assertEquals("https://www.instagram.com/stories/dungeontrain/1/", spans.get(0).url());
        assertEquals("Facebook", spans.get(1).name());
        assertEquals("Your death is on our ", text.substring(0, spans.get(0).start()));
    }

    @Test
    void ignoresNonHttpsAndPlainBrackets() {
        assertEquals(0, ChatMessageList.markdownLinks("see [this](http://x.test/a) and [that] (https://y)").size());
        assertEquals(0, ChatMessageList.markdownLinks(null).size());
    }
}
