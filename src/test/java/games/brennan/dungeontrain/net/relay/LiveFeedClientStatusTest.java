package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveFeedClientStatusTest {

    private static LiveFeedClient.Status parse(String json) {
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();
        return LiveFeedClient.parseStatus(new LiveFeedClient.Result(200, body, null));
    }

    @Test
    void aStoppedStreamIsStillOnAirWithWhereToGoAfterItsTail() {
        LiveFeedClient.Status s = parse("{\"ok\":true,\"live\":true,\"session\":\"s1\",\"playlistUrl\":\"https://x/main/s1/live.m3u8\","
            + "\"streamer\":\"Alice\",\"ended\":true,\"replayUrl\":\"https://x/main/s1/seg00004.ts\"}");
        assertTrue(s.live());
        assertTrue(s.ended());
        assertEquals("https://x/main/s1/live.m3u8", s.playlistUrl());
        assertEquals("https://x/main/s1/seg00004.ts", s.replayUrl());
    }

    @Test
    void aRunningStreamIsNotEndedAndCarriesNoReplay() {
        LiveFeedClient.Status s = parse("{\"ok\":true,\"live\":true,\"session\":\"s1\",\"playlistUrl\":\"https://x/main/s1/live.m3u8\","
            + "\"replayUrl\":\"https://x/old.ts\"}");
        assertFalse(s.ended(), "an older relay never says ended");
        assertNull(s.replayUrl());
    }

    @Test
    void offAirIsNeverEnded() {
        LiveFeedClient.Status s = parse("{\"ok\":true,\"live\":false,\"replayUrl\":\"https://x/main/s1/seg00004.ts\"}");
        assertFalse(s.live());
        assertFalse(s.ended());
        assertEquals("https://x/main/s1/seg00004.ts", s.replayUrl());
    }
}
