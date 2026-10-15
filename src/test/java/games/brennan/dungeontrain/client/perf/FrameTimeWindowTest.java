package games.brennan.dungeontrain.client.perf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameTimeWindowTest {

    private static final long MS = 1_000_000L;
    private static final FrameTimeWindow.Context TEN_SECONDS =
        new FrameTimeWindow.Context(10_000 * MS, "60", false, true, "none");

    @Test
    @DisplayName("An empty window reports zeros, never NaN or a divide-by-zero")
    void emptyWindow() {
        FrameTimeWindow.Snapshot s = new FrameTimeWindow().drain();
        assertEquals(0, s.frames());
        assertEquals(0, s.p99Ms());
        String line = FrameTimeWindow.format(s, TEN_SECONDS);
        assertTrue(line.startsWith("[frame] fps=0.0 "), line);
        assertTrue(line.contains(" ms avg=0.0 p50=0.0 p95=0.0 p99=0.0 max=0.0 "), line);
    }

    @Test
    @DisplayName("Percentiles land on the 1 ms bucket's upper edge, and a few hitches show only in p99 and max")
    void percentilesAndHitches() {
        FrameTimeWindow w = new FrameTimeWindow();
        for (int i = 0; i < 97; i++) w.record(16 * MS + 600_000, false); // 16.6 ms
        w.record(40 * MS, false);
        w.record(60 * MS, false);
        w.record(120 * MS, false);

        FrameTimeWindow.Snapshot s = w.drain();
        assertEquals(100, s.frames());
        assertEquals(17.0, s.p50Ms());
        assertEquals(17.0, s.p95Ms());
        assertEquals(61.0, s.p99Ms()); // the 99th of 100 frames is the 60 ms one
        assertEquals(120 * MS, s.maxNs());
        assertEquals(3, s.over33());
        assertEquals(2, s.over50());
        assertEquals(1, s.over100());
    }

    @Test
    @DisplayName("A percentile never reads above the window's true maximum")
    void percentileClampedToMax() {
        FrameTimeWindow w = new FrameTimeWindow();
        w.record(16 * MS + 100_000, false); // 16.1 ms, bucket upper edge would say 17
        assertEquals(16.1, w.drain().p50Ms(), 1e-9);
    }

    @Test
    @DisplayName("Frames past the histogram fall in the overflow bucket, which reports the real maximum")
    void overflowBucket() {
        FrameTimeWindow w = new FrameTimeWindow();
        w.record(16 * MS, false);
        w.record(900 * MS, false);
        FrameTimeWindow.Snapshot s = w.drain();
        assertEquals(900.0, s.p99Ms());
        assertEquals(900 * MS, s.maxNs());
    }

    @Test
    @DisplayName("drain resets every counter for the next window")
    void drainResets() {
        FrameTimeWindow w = new FrameTimeWindow();
        w.record(200 * MS, true);
        w.drain();
        FrameTimeWindow.Snapshot s = w.drain();
        assertEquals(0, s.frames());
        assertEquals(0, s.pausedFrames());
        assertEquals(0, s.maxNs());
        assertEquals(0, s.over100());
        assertEquals(0, s.p50Ms());
    }

    @Test
    @DisplayName("Non-positive intervals (clock oddities) are ignored")
    void nonPositiveIgnored() {
        FrameTimeWindow w = new FrameTimeWindow();
        w.record(0, false);
        w.record(-5 * MS, false);
        assertEquals(0, w.drain().frames());
    }

    @Test
    @DisplayName("The line carries measured fps, the cap, vsync, pause, focus and screen")
    void lineFormat() {
        FrameTimeWindow w = new FrameTimeWindow();
        for (int i = 0; i < 600; i++) w.record(16 * MS + 600_000, i < 10);
        String line = FrameTimeWindow.format(w.drain(),
            new FrameTimeWindow.Context(10_000 * MS, "max", true, false, "PauseScreen"));
        assertEquals("[frame] fps=60.0 cap=max vsync=on frames=600 ms avg=16.6 p50=16.6 p95=16.6 p99=16.6"
            + " max=16.6 over33=0 over50=0 over100=0 paused=10 focused=no screen=PauseScreen", line);
    }
}
