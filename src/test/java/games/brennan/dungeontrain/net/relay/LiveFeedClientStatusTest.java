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
    void variantsAreParsedSortedAndJunkDropped() {
        LiveFeedClient.Status s = parse("{\"ok\":true,\"live\":true,\"session\":\"s1\",\"playlistUrl\":\"https://x/main/s1/live.m3u8\","
            + "\"variants\":[{\"width\":640,\"height\":360,\"playlistUrl\":\"https://x/main/s1/r640/live.m3u8\"},"
            + "{\"width\":320,\"playlistUrl\":\"https://x/main/s1/r320/live.m3u8\"},"
            + "{\"width\":321,\"playlistUrl\":\"https://x/odd.m3u8\"},"
            + "{\"width\":480,\"playlistUrl\":\"http://x/plain.m3u8\"},"
            + "{\"width\":\"big\",\"playlistUrl\":\"https://x/nan.m3u8\"},"
            + "{\"playlistUrl\":\"https://x/nowidth.m3u8\"}, 7]}");
        assertEquals(2, s.variants().size());
        assertEquals(320, s.variants().get(0).width(), "smallest first");
        assertEquals(180, s.variants().get(0).height(), "height defaults to 16:9");
        assertEquals(640, s.variants().get(1).width());
        assertEquals("https://x/main/s1/r640/live.m3u8", s.variants().get(1).playlistUrl());
    }

    @Test
    void mediaUrlForPicksTheSmallestVariantThatCoversTheDecodeWidthElseTheSource() {
        LiveFeedClient.Status s = parse("{\"ok\":true,\"live\":true,\"session\":\"s1\",\"playlistUrl\":\"https://x/main/s1/live.m3u8\","
            + "\"variants\":[{\"width\":640,\"playlistUrl\":\"https://x/r640.m3u8\"},{\"width\":320,\"playlistUrl\":\"https://x/r320.m3u8\"}]}");
        assertEquals("https://x/r320.m3u8", s.mediaUrlFor(160), "the pinned box");
        assertEquals("https://x/r320.m3u8", s.mediaUrlFor(320));
        assertEquals("https://x/r640.m3u8", s.mediaUrlFor(400), "never a variant narrower than the decode");
        assertEquals("https://x/r640.m3u8", s.mediaUrlFor(640), "a TV at the default viewer width");
        assertEquals("https://x/main/s1/live.m3u8", s.mediaUrlFor(800), "wider than every variant: the source");
        LiveFeedClient.Status none = parse("{\"ok\":true,\"live\":true,\"session\":\"s1\",\"playlistUrl\":\"https://x/main/s1/live.m3u8\"}");
        assertTrue(none.variants().isEmpty(), "an older relay lists none");
        assertEquals("https://x/main/s1/live.m3u8", none.mediaUrlFor(160));
        assertTrue(LiveFeedClient.Status.OFFLINE.variants().isEmpty());
    }

    @Test
    void offAirIsNeverEnded() {
        LiveFeedClient.Status s = parse("{\"ok\":true,\"live\":false,\"replayUrl\":\"https://x/main/s1/seg00004.ts\"}");
        assertFalse(s.live());
        assertFalse(s.ended());
        assertEquals("https://x/main/s1/seg00004.ts", s.replayUrl());
    }
}
