package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveFeedClientChatParseTest {

    private static ChatPage parse(int status, String json) {
        JsonObject body = json == null ? null : JsonParser.parseString(json).getAsJsonObject();
        return LiveFeedClient.parseChat(new LiveFeedClient.Result(status, body, null));
    }

    @Test
    @DisplayName("a page: live flag, head seq, and each line with its source")
    void page() {
        ChatPage p = parse(200, """
            {"ok":true,"live":true,"seq":7,"messages":[
              {"seq":6,"ts":1000,"author":"Viewer","uuid":null,"content":"hello","source":"discord"},
              {"seq":7,"ts":1001,"author":"Alice","uuid":"11111111222233334444555555555555","content":"hi","source":"game"},
              {"seq":8,"ts":1002,"author":"","content":"   ","source":"discord"}
            ]}""");
        assertTrue(p.live());
        assertEquals(7, p.seq());
        assertEquals(2, p.lines().size(), "a blank line is dropped");
        assertEquals("Viewer", p.lines().get(0).author());
        assertNull(p.lines().get(0).uuid());
        assertFalse(p.lines().get(0).fromGame());
        assertEquals("11111111222233334444555555555555", p.lines().get(1).uuid());
        assertTrue(p.lines().get(1).fromGame());
    }

    @Test
    @DisplayName("the head-only reply (no since) carries the seq and no lines; a nameless author reads Someone")
    void headOnly() {
        ChatPage p = parse(200, "{\"ok\":true,\"live\":false,\"seq\":42,\"messages\":[]}");
        assertFalse(p.live());
        assertEquals(42, p.seq());
        assertTrue(p.lines().isEmpty());
        ChatPage q = parse(200, "{\"ok\":true,\"seq\":1,\"messages\":[{\"seq\":1,\"content\":\"x\"}]}");
        assertEquals("Someone", q.lines().get(0).author());
    }

    @Test
    @DisplayName("failures and malformed bodies are EMPTY with seq -1, so the cursor is left alone")
    void failures() {
        assertEquals(-1, parse(503, "{\"error\":\"live_chat_unconfigured\"}").seq());
        assertEquals(-1, parse(0, null).seq());
        assertEquals(-1, parse(200, "{\"ok\":true,\"seq\":\"nope\"}").seq());
    }
}
