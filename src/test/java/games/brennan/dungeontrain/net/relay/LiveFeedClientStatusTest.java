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
    void theStatusPathRanksAViewerOnlyWhenAsked() {
        java.util.UUID id = java.util.UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        String life = "0123456789abcdef0123456789abcdef";
        LiveFeedClient.Life ranked = new LiveFeedClient.Life(life, false, "0.1268.2");
        assertEquals("/live/status", LiveFeedClient.statusPath(null, ranked));
        assertEquals("/live/status?viewer=" + id, LiveFeedClient.statusPath(id, null));
        assertEquals("/live/status?viewer=" + id + "&lb=1&life=" + life + "&fp=0&v=0.1268.2",
            LiveFeedClient.statusPath(id, ranked));
        assertEquals("/live/status?viewer=" + id + "&lb=1&life=" + life + "&fp=1&v=0.1268.2%2Bdev",
            LiveFeedClient.statusPath(id, new LiveFeedClient.Life(life, true, "0.1268.2+dev")));
        // A server that never said which life: all-time only.
        assertEquals("/live/status?viewer=" + id + "&lb=1",
            LiveFeedClient.statusPath(id, new LiveFeedClient.Life("", false, "0.1268.2")));
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
        LiveFeedClient.Status local = parse("{\"ok\":true,\"live\":true,\"session\":\"s1\",\"playlistUrl\":\"http://127.0.0.1:3412/test/main/s1/live.m3u8\","
            + "\"variants\":[{\"width\":320,\"playlistUrl\":\"http://127.0.0.1:3412/test/main/s1/r320/live.m3u8\"}]}");
        assertEquals(1, local.variants().size(), "a plain-http variant is fine when the source is plain http too (local relay)");
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
