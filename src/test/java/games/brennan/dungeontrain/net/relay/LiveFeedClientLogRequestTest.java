package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonParser;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatPage;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.LogRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveFeedClientLogRequestTest {

    private static ChatPage parse(String json) {
        return LiveFeedClient.parseChat(new LiveFeedClient.Result(200, JsonParser.parseString(json).getAsJsonObject(), null));
    }

    @Test
    @DisplayName("a log-request line carries its target and id; a plain line and a malformed one carry none")
    void parsed() {
        ChatPage p = parse("""
            {"live":true,"seq":3,"messages":[
              {"seq":1,"author":"System","content":"The developer asked for Steve's logs","source":"system","cmd":"log_request","target":"Steve","reqId":"lr-1"},
              {"seq":2,"author":"Viewer","content":"/bug","source":"discord"},
              {"seq":3,"author":"System","content":"x","source":"system","cmd":"log_request"}
            ]}""");
        LogRequest r = p.lines().get(0).logRequest();
        assertEquals("Steve", r.target());
        assertEquals("lr-1", r.id());
        assertNull(p.lines().get(1).logRequest(), "a viewer typing /bug is just chat");
        assertNull(p.lines().get(2).logRequest(), "no id → no request");
    }

    @Test
    @DisplayName("no target asks the streamer; a target asks only that player, case-insensitively")
    void targeting() {
        LogRequest streamer = new LogRequest("", "a");
        assertTrue(streamer.isFor("Alice", true));
        assertFalse(streamer.isFor("Alice", false));
        LogRequest named = new LogRequest("steve", "b");
        assertTrue(named.isFor("Steve", false));
        assertFalse(named.isFor("Alice", true), "the streamer ignores a request for someone else");
    }
}
