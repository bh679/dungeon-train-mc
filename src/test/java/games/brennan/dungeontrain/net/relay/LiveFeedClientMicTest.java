package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.MicPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveFeedClientMicTest {

    private static MicPolicy mic(String json) {
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();
        return LiveFeedClient.parseMic(new LiveFeedClient.Result(200, body, null));
    }

    @Test
    void anOlderRelayWithoutMicAllowsItWithoutTwitch() {
        assertEquals(MicPolicy.DEFAULT, mic("{\"session\":\"s\",\"token\":\"t\",\"playlistUrl\":\"u\"}"));
        assertTrue(MicPolicy.DEFAULT.allowed());
        assertFalse(MicPolicy.DEFAULT.twitch());
        assertEquals(MicPolicy.DEFAULT, LiveFeedClient.parseMic(new LiveFeedClient.Result(0, null, new RuntimeException())));
    }

    @Test
    void theRelayCanBarTheMicOrSendItToTwitch() {
        assertEquals(new MicPolicy(false, false), mic("{\"mic\":{\"allowed\":false,\"twitch\":false}}"));
        assertEquals(new MicPolicy(true, true), mic("{\"mic\":{\"allowed\":true,\"twitch\":true}}"));
    }

    @Test
    void aMalformedMicReadsAsTheDefaultField() {
        assertEquals(MicPolicy.DEFAULT, mic("{\"mic\":true}"));
        assertEquals(new MicPolicy(true, false), mic("{\"mic\":{\"allowed\":\"no\"}}"), "a non-boolean allowed is not a ban");
        assertEquals(new MicPolicy(false, false), mic("{\"mic\":{\"allowed\":false}}"));
    }

    @Test
    void theClaimSaysWhetherThisClientCanStreamAMic() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertTrue(LiveFeedClient.claimBody(id, "Moth", "eye", null, null, false, null, null, true).get("mic").getAsBoolean());
        assertFalse(LiveFeedClient.claimBody(id, "Moth", "eye", null, null, false, null, null, false).get("mic").getAsBoolean());
    }

    @Test
    void theHeartbeatCarriesTheMicSwitch() {
        assertTrue(LiveFeedClient.presignBody("tok", List.of("seg00001.ts"), true).get("micOn").getAsBoolean());
        JsonObject off = LiveFeedClient.presignBody("tok", List.of("seg00001.ts"), false);
        assertFalse(off.get("micOn").getAsBoolean());
        assertEquals("tok", off.get("token").getAsString());
        assertEquals(1, off.getAsJsonArray("files").size());
    }
}
