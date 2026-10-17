package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegWaitTest {

    @Test
    void aDownloadReportingNothingStallsAfterTheCap() {
        FfmpegWait w = new FfmpegWait();
        long t = 1_000_000L;
        w.reset(t);
        assertFalse(w.tick(-1, t), "first sample sets the baseline");
        assertFalse(w.tick(-1, t + FfmpegWait.STALL_MS - 1), "not yet");
        assertTrue(w.tick(-1, t + FfmpegWait.STALL_MS), "unknown progress for the whole cap: stalled");
        assertEquals(FfmpegWait.STALL_MS, w.elapsedMs(t + FfmpegWait.STALL_MS));
        assertEquals(-1, w.lastProgress());
    }

    @Test
    void aMovingPercentageResetsTheClock() {
        FfmpegWait w = new FfmpegWait();
        long t = 0;
        w.reset(t);
        assertFalse(w.tick(10, t));
        assertFalse(w.tick(10, t + 80_000));
        assertFalse(w.tick(11, t + 85_000), "progress moved: a slow download is not a stalled one");
        assertFalse(w.tick(11, t + 85_000 + FfmpegWait.STALL_MS - 1));
        assertTrue(w.tick(11, t + 85_000 + FfmpegWait.STALL_MS));
        assertEquals(11, w.lastProgress());
    }

    @Test
    void resetForgetsTheEarlierStall() {
        FfmpegWait w = new FfmpegWait();
        w.reset(0);
        assertFalse(w.tick(5, 0));
        assertTrue(w.tick(5, FfmpegWait.STALL_MS));
        w.reset(FfmpegWait.STALL_MS);
        assertFalse(w.tick(5, FfmpegWait.STALL_MS), "a new stream starts a new wait");
        assertFalse(w.tick(5, FfmpegWait.STALL_MS + 1_000));
    }
}
