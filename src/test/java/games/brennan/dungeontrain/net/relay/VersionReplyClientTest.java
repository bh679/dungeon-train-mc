package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.net.relay.VersionReplyClient.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VersionReplyClientTest {

    @Test
    @DisplayName("the reply body is the shape the relay's /dev/version-reply reads")
    void body() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        JsonObject o = JsonParser.parseString(
                VersionReplyClient.body("req123", uuid, "Steve", Status.KID, "Kid mode is on")).getAsJsonObject();
        assertEquals("req123", o.get("reqId").getAsString());
        assertEquals(uuid.toString(), o.get("uuid").getAsString());
        assertEquals("Steve", o.get("name").getAsString());
        assertEquals("kid", o.get("status").getAsString());
        assertEquals("Kid mode is on", o.get("text").getAsString());
    }
}
