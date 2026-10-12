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
    void aCleanExitAfterFramesIsTheEndOfTheStream() {
        assertTrue(LiveHlsSession.reachedEnd(0, 120, false), "ffmpeg played the playlist to its ENDLIST");
    }

    @Test
    void errorsAndEmptyRunsAreRetried() {
        assertFalse(LiveHlsSession.reachedEnd(8, 120, false), "a 404 or network drop exits non-zero");
        assertFalse(LiveHlsSession.reachedEnd(0, 0, false), "nothing decoded: try again");
    }

    @Test
    void aReplayLoopNeverEnds() {
        assertFalse(LiveHlsSession.reachedEnd(0, 120, true));
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
