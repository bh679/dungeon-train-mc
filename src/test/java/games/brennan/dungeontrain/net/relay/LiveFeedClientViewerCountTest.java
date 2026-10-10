package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveFeedClientViewerCountTest {

    private static LiveFeedClient.Result reply(int status, String json) {
        JsonObject body = json == null ? null : JsonParser.parseString(json).getAsJsonObject();
        return new LiveFeedClient.Result(status, body, null);
    }

    @Test
    void readsTheTotalFromAPresignReply() {
        assertEquals(3, LiveFeedClient.parseViewerCount(
            reply(200, "{\"ok\":true,\"session\":\"s\",\"urls\":{},\"viewers\":{\"total\":3,\"game\":2,\"web\":1}}")));
        assertEquals(0, LiveFeedClient.parseViewerCount(reply(200, "{\"viewers\":{\"total\":0}}")));
    }

    @Test
    void unknownWhenTheRelaySaysNothingUsable() {
        assertEquals(-1, LiveFeedClient.parseViewerCount(reply(200, "{\"ok\":true,\"urls\":{}}")), "an older relay");
        assertEquals(-1, LiveFeedClient.parseViewerCount(reply(200, "{\"viewers\":5}")));
        assertEquals(-1, LiveFeedClient.parseViewerCount(reply(200, "{\"viewers\":{\"total\":\"lots\"}}")));
        assertEquals(-1, LiveFeedClient.parseViewerCount(reply(403, "{\"error\":\"forbidden\"}")));
        assertEquals(-1, LiveFeedClient.parseViewerCount(reply(0, null)));
    }

    @Test
    void aNegativeTotalReadsAsZero() {
        assertEquals(0, LiveFeedClient.parseViewerCount(reply(200, "{\"viewers\":{\"total\":-4}}")));
    }
}
