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

    @Test
    void theStreamersTwitchRidesOnStatusAndClaim() {
        LiveFeedClient.Status s = parse("{\"ok\":true,\"live\":true,\"session\":\"s1\",\"playlistUrl\":\"https://x/main/s1/live.m3u8\","
            + "\"streamer\":\"Pat\",\"twitch\":{\"login\":\"Pat_1\",\"url\":\"https://evil/\"}}");
        assertEquals("pat_1", s.twitchLogin());
        assertNull(parse("{\"ok\":true,\"live\":true,\"session\":\"s1\",\"playlistUrl\":\"https://x/l.m3u8\",\"streamer\":\"Sam\"}").twitchLogin());
        assertNull(parse("{\"ok\":true,\"live\":true,\"session\":\"s1\",\"playlistUrl\":\"https://x/l.m3u8\",\"twitch\":{\"login\":\"a b\"}}").twitchLogin());

        JsonObject claim = JsonParser.parseString("{\"ok\":true,\"session\":\"s1\",\"token\":\"t\",\"playlistUrl\":\"https://x/l.m3u8\","
            + "\"twitch\":{\"login\":\"pat_1\",\"restream\":true,\"setTitle\":false}}").getAsJsonObject();
        LiveFeedClient.Claim c = LiveFeedClient.parseClaim(new LiveFeedClient.Result(200, claim, null));
        assertEquals(new LiveFeedClient.Twitch("pat_1", "https://twitch.tv/pat_1", true, false), c.twitch());
        JsonObject bare = JsonParser.parseString("{\"ok\":true,\"session\":\"s1\",\"token\":\"t\",\"playlistUrl\":\"https://x/l.m3u8\",\"twitch\":null}").getAsJsonObject();
        assertNull(LiveFeedClient.parseClaim(new LiveFeedClient.Result(200, bare, null)).twitch());
    }

    @Test
    void theClaimMaySetTheStreamsCadence() {
        String base = "{\"ok\":true,\"session\":\"s1\",\"token\":\"t\",\"playlistUrl\":\"https://x/l.m3u8\"";
        LiveFeedClient.Claim quick = claim(base + ",\"segmentSeconds\":2,\"listSize\":30}");
        assertEquals(2, quick.segmentSeconds());
        assertEquals(30, quick.listSize());
        LiveFeedClient.Claim plain = claim(base + "}");
        assertEquals(0, plain.segmentSeconds(), "absent: the client config decides");
        assertEquals(0, plain.listSize());
        for (String bad : new String[] {"1", "31", "2.5", "\"2\"", "null", "-2"}) {
            assertEquals(0, claim(base + ",\"segmentSeconds\":" + bad + "}").segmentSeconds(), bad);
        }
        assertEquals(0, claim(base + ",\"segmentSeconds\":2,\"listSize\":500}").listSize(), "a window out of range reads as unset");
        assertEquals(0, claim(base + ",\"listSize\":30}").listSize(), "a window without a segment length is ignored");
    }

    private static LiveFeedClient.Claim claim(String json) {
        return LiveFeedClient.parseClaim(new LiveFeedClient.Result(200, JsonParser.parseString(json).getAsJsonObject(), null));
    }

    @Test
    void aPlaylistUrlThatIsNotHttpsHlsReadsOffAir() {
        for (String bad : new String[] {"http://evil.example/main/s1/live.m3u8", "file:///etc/live.m3u8",
                "https://x/main/s1/live.mp4", "ftp://x/live.m3u8", "https:///live.m3u8", "https://u@x/live.m3u8"}) {
            LiveFeedClient.Status s = parse("{\"ok\":true,\"live\":true,\"session\":\"s1\",\"playlistUrl\":\"" + bad + "\"}");
            assertFalse(s.live(), bad);
            assertNull(s.playlistUrl(), bad);
        }
        LiveFeedClient.Status local = parse("{\"ok\":true,\"live\":true,\"session\":\"s1\","
            + "\"playlistUrl\":\"http://127.0.0.1:3412/test/main/s1/live.m3u8\"}");
        assertTrue(local.live(), "a local relay's stand-in bucket is plain http on loopback");
    }

    @Test
    void aReplayUrlThatIsNotAnHttpsSegmentIsDropped() {
        assertNull(parse("{\"ok\":true,\"live\":false,\"replayUrl\":\"http://evil.example/seg00004.ts\"}").replayUrl());
        assertNull(parse("{\"ok\":true,\"live\":false,\"replayUrl\":\"https://x/live.m3u8\"}").replayUrl(), "not a segment");
        LiveFeedClient.Status ended = parse("{\"ok\":true,\"live\":true,\"session\":\"s1\",\"playlistUrl\":\"https://x/main/s1/live.m3u8\","
            + "\"ended\":true,\"replayUrl\":\"file:///seg00004.ts\"}");
        assertTrue(ended.ended());
        assertNull(ended.replayUrl());
    }

    @Test
    void presignDropsAPutTargetThatIsNotHttps() {
        JsonObject body = JsonParser.parseString("{\"urls\":{"
            + "\"seg00001.ts\":{\"url\":\"https://acct.r2.cloudflarestorage.com/dt-live/seg00001.ts?X-Amz-Signature=a\"},"
            + "\"live.m3u8\":{\"url\":\"http://evil.example/live.m3u8?sig=b\"}}}").getAsJsonObject();
        java.util.Map<String, LiveFeedClient.SignedPut> puts = LiveFeedClient.parsePresign(new LiveFeedClient.Result(200, body, null));
        assertEquals(java.util.Set.of("seg00001.ts"), puts.keySet());
        LiveFeedClient.Result r = LiveFeedClient.upload(new LiveFeedClient.SignedPut("http://evil.example/x", java.util.Map.of(), 0),
            java.nio.file.Path.of("missing")).join();
        assertEquals(0, r.status());
        assertTrue(r.error() instanceof IllegalArgumentException);
    }
}
