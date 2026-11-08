package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatLine;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Source;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveChatSourcesTest {

    @AfterEach
    void clear() {
        LiveChatSources.setForTest(Map.of());
    }

    @Test
    @DisplayName("parse: label, #rrggbb colour and a relay icon path per source; anything malformed is left out")
    void parse() {
        Map<String, LiveChatSources.Entry> m = LiveChatSources.parse(LiveChatSources.json("""
            {"sources":{
              "youtube":{"label":"YouTube","color":"#ff0000","icon":"/live/chat/sources/youtube.png?v=abc123"},
              "nocolor":{"label":"N","color":"red","icon":"https://evil.example/x.png"},
              "Bad Id":{"label":"x","color":"#000000"},
              "nolabel":{"color":"#00ff00"}
            }}"""));
        assertEquals(Map.of("youtube", "YouTube", "nocolor", "N", "nolabel", "nolabel"),
            Map.of("youtube", m.get("youtube").label(), "nocolor", m.get("nocolor").label(), "nolabel", m.get("nolabel").label()));
        assertEquals(3, m.size(), "a malformed id is dropped");
        assertEquals(TextColor.fromRgb(0xFF0000), m.get("youtube").color());
        assertEquals("src:/live/chat/sources/youtube.png?v=abc123", m.get("youtube").iconKey());
        assertNull(m.get("nocolor").color(), "only #rrggbb");
        assertNull(m.get("nocolor").iconKey(), "an icon from anywhere but the relay's own sources path is never fetched");
        assertTrue(LiveChatSources.parse(LiveChatSources.json("{}")).isEmpty());
        assertTrue(LiveChatSources.parse(null).isEmpty());
    }

    @Test
    @DisplayName("line: a source this build doesn't know takes the catalog's colour, and the bubble until its icon loads")
    void catalogSourceLine() {
        LiveChatSources.setForTest(Map.of("youtube", new LiveChatSources.Entry("YouTube", TextColor.fromRgb(0xFF0000), "src:/live/chat/sources/youtube.png")));
        ChatLine y = new ChatLine(1, 0, "Tuber", null, "hi", Source.OTHER, null, null, null, null, null, null, null, null, null, "youtube");
        var out = LiveChatRules.line(y);
        assertEquals("chat.dungeontrain.live_chat.prefix  Tuber: hi", out.getString());
        assertEquals(TextColor.fromRgb(0xFF0000), out.getSiblings().get(2).getStyle().getColor());
        ChatLine unknown = new ChatLine(2, 0, "Who", null, "hi", Source.OTHER, null, null, null, null, null, null, null, null, null, "matrix");
        assertEquals(LiveChatRules.nameColour(Source.OTHER).getColor(), LiveChatRules.line(unknown).getSiblings().get(2).getStyle().getColor().getValue(),
            "no catalog entry: the plain OTHER look");
    }
}
