package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpeg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LiveHlsSessionTest {

    @Test
    void aCleanExitAfterFramesOnAnEndlistPlaylistIsTheEndOfTheStream() {
        assertTrue(LiveHlsSession.reachedEnd(0, 120, false, true), "ffmpeg played the playlist to its ENDLIST");
    }

    @Test
    void aCleanExitOnAVanishedPlaylistIsRetried() {
        // ffmpeg exits 0 after a live playlist starts answering 404: the stream vanished, it did not end.
        assertFalse(LiveHlsSession.reachedEnd(0, 120, false, false));
    }

    @Test
    void errorsAndEmptyRunsAreRetried() {
        assertFalse(LiveHlsSession.reachedEnd(8, 120, false, true), "a 404 or network drop exits non-zero");
        assertFalse(LiveHlsSession.reachedEnd(0, 0, false, true), "nothing decoded: try again");
    }

    @Test
    void aReplayLoopNeverEnds() {
        assertFalse(LiveHlsSession.reachedEnd(0, 120, true, true));
    }

    @Test
    void onlyAPresentPlaylistWithEndlistMeansEnded() {
        String live = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\nseg00041.ts\n";
        assertTrue(LiveHlsSession.isEndlist(200, live + "#EXT-X-ENDLIST\n"));
        assertFalse(LiveHlsSession.isEndlist(200, live), "still live, just between segments");
        assertFalse(LiveHlsSession.isEndlist(404, null), "deleted, expired or never uploaded");
        assertFalse(LiveHlsSession.isEndlist(403, "#EXT-X-ENDLIST"), "an error page is not a playlist");
    }

    @Test
    void aLiveRunWithNoFrameForTheStallWindowIsStalled() {
        long last = 1_000_000L;
        assertFalse(LiveHlsSession.stalled(last, last + LiveHlsSession.STALL_MS - 1, false, 300),
            "waiting on the next segment is normal");
        assertTrue(LiveHlsSession.stalled(last, last + LiveHlsSession.STALL_MS, false, 300));
        assertTrue(LiveHlsSession.stalled(last, last + 120_000, false, 300));
    }

    @Test
    void noFrameYetOrAReplayIsNeverStalled() {
        long last = 1_000_000L;
        assertFalse(LiveHlsSession.stalled(last, last + 120_000, false, 0), "the waiting pattern is already up");
        assertFalse(LiveHlsSession.stalled(last, last + 120_000, true, 300), "a replay loops a finished segment");
    }

    @Test
    void theStallWindowCoversMoreThanOneSegment() {
        assertTrue(LiveHlsSession.STALL_MS >= 2 * 10_000L, "a viewer buffers up to one 10 s segment legitimately");
    }

    @Test
    void aSessionKeepsTheSourceAsItsIdentityWhileDecodingARendition() {
        LiveHlsSession s = new LiveHlsSession("https://x/main/s1/live.m3u8", "https://x/main/s1/r320/live.m3u8", 320, 180, false);
        try {
            assertEquals("https://x/main/s1/live.m3u8", s.playlistUrl(), "what status, playedToEnd and the own-broadcast check compare");
            assertEquals("https://x/main/s1/r320/live.m3u8", s.mediaUrl(), "what ffmpeg reads");
            LiveHlsSession plain = new LiveHlsSession("https://x/main/s1/live.m3u8", 640, 360, false);
            assertEquals(plain.playlistUrl(), plain.mediaUrl(), "without a rendition the two are one");
            plain.close();
        } finally {
            s.close();
        }
    }

    @Test
    void ffmpegPipesRgbaSoFramesNeedNoConversion() {
        List<String> live = List.of(LiveHlsSession.args("https://x/live.m3u8", 640, 360, false));
        assertTrue(live.contains("rgba"), "live frames arrive as RGBA");
        assertFalse(live.contains("rgb24"));
        assertTrue(String.join(" ", live).contains("-live_start_index -1"), "join at the newest segment");

        List<String> replay = List.of(LiveHlsSession.args("https://x/seg.ts", 640, 360, true));
        assertTrue(replay.contains("rgba"));
        assertFalse(String.join(" ", replay).contains("rgb24"), "the replay filter ends in rgba too");
    }

    @Test
    void closeDuringTheRetryBackoffDoesNotWaitItOut() throws Exception {
        // An "ffmpeg" that fails at once, like a 404 before the first segment: the reader then sleeps 2 s.
        Path fails = Path.of("/usr/bin/false");
        assumeTrue(Files.isExecutable(fails), "needs /usr/bin/false");
        LiveHlsSession session = new LiveHlsSession("http://127.0.0.1:9/live.m3u8", 16, 9);
        session.touch(new FFmpeg(fails, fails));
        Thread.sleep(500);
        assertTrue(session.decoding(), "the reader is in its backoff");

        long start = System.nanoTime();
        session.close();
        long closeMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(closeMs < 100, "close() runs on the render thread; took " + closeMs + " ms");

        for (int i = 0; i < 50 && session.decoding(); i++) Thread.sleep(10);
        assertFalse(session.decoding(), "the interrupt ends the reader instead of leaving it asleep");
    }
}
