package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatPage;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Source;
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
              {"seq":8,"ts":1002,"author":"","content":"   ","source":"discord"},
              {"seq":9,"ts":1003,"author":"Twitcher","uuid":null,"content":"pog","source":"twitch"},
              {"seq":10,"ts":1004,"author":"Mystery","content":"?","source":"matrix"}
            ]}""");
        assertTrue(p.live());
        assertEquals(7, p.seq());
        assertEquals(4, p.lines().size(), "a blank line is dropped");
        assertEquals("Viewer", p.lines().get(0).author());
        assertNull(p.lines().get(0).uuid());
        assertFalse(p.lines().get(0).fromGame());
        assertEquals(Source.DISCORD, p.lines().get(0).source());
        assertEquals("11111111222233334444555555555555", p.lines().get(1).uuid());
        assertTrue(p.lines().get(1).fromGame());
        assertEquals(Source.GAME, p.lines().get(1).source());
        assertEquals(Source.TWITCH, p.lines().get(2).source());
        assertFalse(p.lines().get(2).fromGame());
        assertEquals(Source.OTHER, p.lines().get(3).source(), "an unknown room is still shown");
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
        assertEquals(Source.OTHER, q.lines().get(0).source(), "no source field: other");
    }

    @Test
    @DisplayName("failures and malformed bodies are EMPTY with seq -1, so the cursor is left alone")
    void failures() {
        assertEquals(-1, parse(503, "{\"error\":\"live_chat_unconfigured\"}").seq());
        assertEquals(-1, parse(0, null).seq());
        assertEquals(-1, parse(200, "{\"ok\":true,\"seq\":\"nope\"}").seq());
    }

    @Test
    @DisplayName("deleted: the removed seqs come through; junk entries and a missing field give none")
    void deleted() {
        ChatPage p = parse(200, """
            {"ok":true,"live":true,"seq":9,"deleted":[3,"x",null,7],"messages":[]}""");
        assertEquals(java.util.List.of(3L, 7L), p.deleted());
        assertTrue(parse(200, "{\"ok\":true,\"live\":true,\"seq\":9,\"messages\":[]}").deleted().isEmpty());
        assertTrue(ChatPage.EMPTY.deleted().isEmpty());
    }
}
