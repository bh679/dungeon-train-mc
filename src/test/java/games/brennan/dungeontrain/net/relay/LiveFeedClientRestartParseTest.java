package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatPage;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.RestartNotice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LiveFeedClientRestartParseTest {

    private static ChatPage parse(String json) {
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();
        return LiveFeedClient.parseChat(new LiveFeedClient.Result(200, body, null));
    }

    @Test
    @DisplayName("a relay-restart tag rides on its info line; junk or none leaves a plain line")
    void restartTags() {
        ChatPage p = parse("""
            {"ok":true,"live":true,"seq":6,"messages":[
              {"seq":1,"ts":1000,"author":"Dungeon Train","content":"The server is restarting.","source":"info","restart":{"resumeBy":301000}},
              {"seq":2,"ts":7000,"author":"Dungeon Train","content":"Restarting in 3","source":"info","restart":{"countdown":3}},
              {"seq":3,"ts":9000,"author":"Dungeon Train","content":"1","source":"info","restart":{"countdown":1}},
              {"seq":4,"ts":9000,"author":"Dungeon Train","content":"back","source":"info","restart":{"resumed":true}},
              {"seq":5,"ts":9000,"author":"Dungeon Train","content":"odd","source":"info","restart":{"countdown":"x","resumed":"yes"}},
              {"seq":6,"ts":9000,"author":"Alice","content":"hi","source":"discord"}
            ]}""");
        assertEquals(6, p.lines().size());
        assertEquals(new RestartNotice(301000L, 0, false), p.lines().get(0).restart());
        assertEquals(new RestartNotice(0L, 3, false), p.lines().get(1).restart());
        assertEquals(new RestartNotice(0L, 1, false), p.lines().get(2).restart());
        assertEquals(new RestartNotice(0L, 0, true), p.lines().get(3).restart());
        assertNull(p.lines().get(4).restart(), "nothing usable in the tag");
        assertNull(p.lines().get(5).restart());
    }
}
