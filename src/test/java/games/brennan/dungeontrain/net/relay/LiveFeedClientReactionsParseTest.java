package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonParser;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveFeedClientReactionsParseTest {

    @Test
    @DisplayName("a page's reactions: per line, emoji list with users, and the event that changed it")
    void reactions() {
        ChatPage p = LiveFeedClient.parseChat(new LiveFeedClient.Result(200, JsonParser.parseString("""
            {"ok":true,"live":true,"seq":12,"messages":[],"deleted":[],"reactions":[
              {"line":7,"list":[{"name":"🔥","id":null,"animated":false,"count":3,"users":["Brennan","Sam"]},
                                {"name":"train","id":"123456789","animated":true,"count":1,"users":["Sam"]},
                                {"name":"","count":1}],
               "event":{"action":"add","name":"🔥","id":null,"animated":false,"userName":"Brennan"}},
              {"line":8,"list":[]},
              {"nope":true}
            ]}""").getAsJsonObject(), null));
        assertEquals(2, p.reactions().size(), "an entry with no line is skipped");
        LiveFeedClient.LineReactions first = p.reactions().get(0);
        assertEquals(7, first.line());
        assertEquals(2, first.list().size(), "a nameless emoji is skipped");
        assertEquals(3, first.list().get(0).count());
        assertEquals(List.of("Brennan", "Sam"), first.list().get(0).users());
        assertEquals("123456789", first.list().get(1).id());
        assertTrue(first.list().get(1).custom());
        assertTrue(first.event().isAdd());
        assertEquals("Brennan", first.event().userName());
        assertTrue(p.reactions().get(1).list().isEmpty());
        assertNull(p.reactions().get(1).event());
    }

    @Test
    @DisplayName("an older relay without reactions gives an empty list")
    void absent() {
        ChatPage p = LiveFeedClient.parseChat(new LiveFeedClient.Result(200,
            JsonParser.parseString("{\"ok\":true,\"seq\":3,\"messages\":[]}").getAsJsonObject(), null));
        assertTrue(p.reactions().isEmpty());
    }
}
