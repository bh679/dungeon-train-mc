package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveFeedClientAheadParseTest {

    private static LiveFeedClient.Result reply(int status, String json) {
        JsonObject body = json == null ? null : JsonParser.parseString(json).getAsJsonObject();
        return new LiveFeedClient.Result(status, body, null);
    }

    @Test
    void readsTheSignedAheadBlock() {
        LiveFeedClient.Ahead a = LiveFeedClient.parseAhead(reply(200, "{\"urls\":{},\"ahead\":{\"from\":12,\"expiresAt\":9000,"
            + "\"urls\":{\"seg00012.ts\":{\"url\":\"https://r2.example/a\",\"headers\":{\"content-type\":\"video/mp2t\"},\"expiresAt\":8000},"
            + "\"live.m3u8\":{\"url\":\"https://r2.example/p\"}}}}"));
        assertNotNull(a);
        assertEquals(12, a.from());
        assertEquals(9000, a.expiresAt());
        assertEquals(8000, a.urls().get("seg00012.ts").expiresAt());
        assertEquals("video/mp2t", a.urls().get("seg00012.ts").headers().get("content-type"));
        assertEquals(9000, a.urls().get("live.m3u8").expiresAt(), "an entry without its own expiry takes the block's");
    }

    @Test
    void noBlockFromAnOlderRelayOrAMalformedReply() {
        assertNull(LiveFeedClient.parseAhead(reply(200, "{\"urls\":{}}")));
        assertNull(LiveFeedClient.parseAhead(reply(200, "{\"ahead\":5}")));
        assertNull(LiveFeedClient.parseAhead(reply(200, "{\"ahead\":{\"from\":\"x\",\"urls\":{\"a\":{\"url\":\"https://r2.example/a\"}}}}")));
        assertNull(LiveFeedClient.parseAhead(reply(503, "{\"ahead\":{\"urls\":{}}}")));
        assertNull(LiveFeedClient.parseAhead(reply(0, null)));
    }

    @Test
    void anAheadUrlThatIsNotHttpsIsDropped() {
        LiveFeedClient.Ahead a = LiveFeedClient.parseAhead(reply(200, "{\"ahead\":{\"from\":1,\"expiresAt\":9,"
            + "\"urls\":{\"seg00001.ts\":{\"url\":\"http://evil.example/a\"},\"live.m3u8\":{\"url\":\"https://r2.example/p\"}}}}"));
        assertNotNull(a);
        assertEquals(Map.of("live.m3u8", a.urls().get("live.m3u8")), a.urls());
    }

    @Test
    void readsRefusedFiles() {
        assertEquals(Map.of("seg00003.ts", "stale"),
            LiveFeedClient.parseRefused(reply(200, "{\"urls\":{},\"refused\":{\"seg00003.ts\":\"stale\"}}")));
        assertTrue(LiveFeedClient.parseRefused(reply(200, "{\"urls\":{}}")).isEmpty());
        assertTrue(LiveFeedClient.parseRefused(reply(500, null)).isEmpty());
    }
}
