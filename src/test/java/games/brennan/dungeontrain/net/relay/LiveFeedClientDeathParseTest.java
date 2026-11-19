package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatPage;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Death;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LiveFeedClientDeathParseTest {

    private static ChatPage parse(String json) {
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();
        return LiveFeedClient.parseChat(new LiveFeedClient.Result(200, body, null));
    }

    @Test
    @DisplayName("a TV watcher's death tag rides on its info line; a nameless or junk tag leaves a plain line")
    void deathTags() {
        String big = "x".repeat(LiveFeedClient.MAX_DEATH_MESSAGE);
        ChatPage p = parse("""
            {"ok":true,"live":true,"seq":5,"messages":[
              {"seq":1,"author":"Dungeon Train","content":"Steve was slain by Zombie while watching the stream","source":"info",
               "death":{"name":"Steve","message":{"translate":"death.attack.mob","with":[{"text":"Steve"},{"text":"Zombie"}]}}},
              {"seq":2,"author":"Dungeon Train","content":"Alex died while watching the stream","source":"info","death":{"name":"Alex"}},
              {"seq":3,"author":"Dungeon Train","content":"odd","source":"info","death":{"name":"  ","message":{}}},
              {"seq":4,"author":"Dungeon Train","content":"odd","source":"info","death":"Steve"},
              {"seq":5,"author":"Dungeon Train","content":"huge","source":"info","death":{"name":"Rook","message":{"text":"%s"}}}
            ]}""".formatted(big));
        assertEquals(5, p.lines().size());
        Death steve = p.lines().get(0).death();
        assertNotNull(steve);
        assertEquals("Steve", steve.name());
        assertNotNull(steve.message());
        assertEquals("death.attack.mob", JsonParser.parseString(steve.message()).getAsJsonObject().get("translate").getAsString());
        assertEquals(new Death("Alex", null), p.lines().get(1).death(), "text only");
        assertNull(p.lines().get(2).death(), "no name");
        assertNull(p.lines().get(3).death(), "not an object");
        assertEquals(new Death("Rook", null), p.lines().get(4).death(), "oversized message dropped, line kept");
    }
}
