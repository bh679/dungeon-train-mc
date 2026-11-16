package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class LiveFeedClientLinkedChannelTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void readsTheLoginLowerCased() {
        assertEquals(new LiveFeedClient.LinkedChannel("ashseven", "AshSeven"),
            LiveFeedClient.parseChannel(json("{\"channel\":{\"login\":\"AshSeven\",\"name\":\"AshSeven\"}}")));
    }

    @Test
    void aLineWithoutOneHasNone() {
        assertNull(LiveFeedClient.parseChannel(json("{\"content\":\"hi\"}")));
        assertNull(LiveFeedClient.parseChannel(json("{\"channel\":\"ashseven\"}")));
    }

    @Test
    void aLoginOutsideTwitchsAlphabetIsRefused() {
        assertNull(LiveFeedClient.parseChannel(json("{\"channel\":{\"login\":\"evil.com/x\",\"name\":\"Bo\"}}")));
        assertNull(LiveFeedClient.parseChannel(json("{\"channel\":{\"name\":\"Bo\"}}")));
    }
}
