package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivePerfWindowTest {

    private static final long MS = 1_000_000L;

    @Test
    void drainSumsKeepsTheMaxAndResets() {
        LivePerfWindow w = new LivePerfWindow();
        w.grab(1 * MS);
        w.grab(3 * MS);
        w.grabSkipped();
        w.encoderWrote(true);
        w.encoderWrote(false);
        w.encoderWrote(true);
        w.frame(10 * MS);
        w.frame(25 * MS);

        LivePerfWindow.Snapshot s = w.drain();
        assertEquals(2, s.grabs());
        assertEquals(4 * MS, s.grabNs());
        assertEquals(3 * MS, s.grabMaxNs());
        assertEquals(1, s.skipped());
        assertEquals(2, s.encFresh());
        assertEquals(1, s.encDup());
        assertEquals(25 * MS, s.worstFrameNs());

        LivePerfWindow.Snapshot next = w.drain();
        assertEquals(0, next.grabs());
        assertEquals(0, next.grabMaxNs());
        assertEquals(0, next.worstFrameNs());
    }

    @Test
    void cpuPercentIsOfOneCore() {
        assertEquals(25.0, LivePerfWindow.cpuPercent(Duration.ofSeconds(10), Duration.ofSeconds(25), 60_000 * MS), 1e-9);
        assertEquals(150.0, LivePerfWindow.cpuPercent(Duration.ZERO, Duration.ofSeconds(90), 60_000 * MS), 1e-9);
    }

    @Test
    void cpuPercentIsNullWhenItCannotBeKnown() {
        assertNull(LivePerfWindow.cpuPercent(null, Duration.ofSeconds(1), 60_000 * MS));
        assertNull(LivePerfWindow.cpuPercent(Duration.ofSeconds(1), null, 60_000 * MS));
        assertNull(LivePerfWindow.cpuPercent(Duration.ZERO, Duration.ofSeconds(1), 0));
        assertNull(LivePerfWindow.cpuPercent(Duration.ofSeconds(5), Duration.ofSeconds(1), 60_000 * MS));
    }

    @Test
    void streamerLine() {
        LivePerfWindow w = new LivePerfWindow();
        w.grab(MS);
        w.encoderWrote(true);
        w.frame(20 * MS);
        String line = LivePerfWindow.format(w.drain(),
            new LivePerfWindow.Context(true, "1280x720@20", 24.4, 0, 0, null, 104));
        assertEquals("[live] role=stream size=1280x720@20 grabs=1 skipped=0 grabMs avg=1.00 max=1.00"
            + " encFrames=1 dup=0 encCpu=24% | fps=104 worstFrameMs=20.00", line);
    }

    @Test
    void viewerLineWithUnknownCpu() {
        LivePerfWindow w = new LivePerfWindow();
        w.decoderRun();
        w.decoded();
        w.decoded();
        w.upload(MS / 2);
        String line = LivePerfWindow.format(w.drain(),
            new LivePerfWindow.Context(false, null, null, 1, 640, null, 90));
        assertEquals("[live] role=view view sessions=1 width=640 decoded=2 stalls=0 runs=1"
            + " uploadMs avg=0.50 max=0.50 decCpu=n/a | fps=90 worstFrameMs=0.00", line);
    }

    @Test
    void theWindowActivityStoppedInSaysEnded() {
        LivePerfWindow w = new LivePerfWindow();
        w.frame(15 * MS);
        String line = LivePerfWindow.format(w.drain(),
            new LivePerfWindow.Context(false, null, null, 0, 0, null, 112));
        assertEquals("[live] role=ended | fps=112 worstFrameMs=15.00", line);
    }

    @Test
    void streamerWatchingTheirOwnBoxIsBoth() {
        LivePerfWindow w = new LivePerfWindow();
        w.grab(MS);
        w.decoded();
        String line = LivePerfWindow.format(w.drain(),
            new LivePerfWindow.Context(true, "960x540@20", 20.0, 1, 480, 6.0, 80));
        assertTrue(line.startsWith("[live] role=stream+view size=960x540@20"), line);
        assertTrue(line.contains("encCpu=20% | view sessions=1 width=480"), line);
        assertTrue(line.contains("decCpu=6%"), line);
        assertFalse(line.contains("n/a"), line);
    }
}
