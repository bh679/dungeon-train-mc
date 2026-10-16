package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The strike body is the contract the relay's /live/moderation/strike reads, and the uuid comes back out of it. */
final class LiveModerationClientTest {

    private static final UUID P = UUID.fromString("3f2a9c1e-0000-4000-8000-00000000abcd");

    @Test
    @DisplayName("strike body: undashed uuid, name, source, terms")
    void body() {
        JsonObject o = JsonParser.parseString(LiveModerationClient.strikeBody(P, "Dev", "chat", List.of("x", "y"), "you x and y")).getAsJsonObject();
        assertEquals("you x and y", o.get("text").getAsString());
        assertEquals("3f2a9c1e000040008000" + "00000000abcd", o.get("uuid").getAsString());
        assertEquals("Dev", o.get("name").getAsString());
        assertEquals("chat", o.get("source").getAsString());
        assertEquals(2, o.getAsJsonArray("terms").size());
    }

    @Test
    @DisplayName("the player is read back from the request body; junk reads as null")
    void playerOf() {
        assertEquals(P, LiveModerationClient.playerOf(LiveModerationClient.strikeBody(P, null, null, null, null)));
        assertEquals(P, LiveModerationClient.playerOf(LiveModerationClient.appealBody(P, "Dev", 7L, "sorry")));
        assertEquals(7, JsonParser.parseString(LiveModerationClient.appealBody(P, "Dev", 7L, "")).getAsJsonObject().get("evidenceId").getAsInt());
        assertNull(LiveModerationClient.playerOf("{\"uuid\":\"short\"}"));
        assertNull(LiveModerationClient.playerOf("nope"));
        assertNull(LiveModerationClient.playerOf(null));
    }
}
