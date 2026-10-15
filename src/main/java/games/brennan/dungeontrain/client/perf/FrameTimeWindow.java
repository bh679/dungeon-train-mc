package games.brennan.dungeontrain.client.perf;

import java.util.Arrays;
import java.util.Locale;

/**
 * One window of client frame intervals for the {@code [frame]} perf line ({@link ClientFramePerf}):
 * measured frame rate, the frame-time spread and how many frames hitched. Pure — no Minecraft types —
 * so the percentile math and the line format are unit-tested on a bare JVM.
 *
 * <p>Recording a frame is one array increment into a 1 ms histogram (0-250 ms, then one overflow
 * bucket) plus a few running sums: no allocation, so the line costs nothing per frame. Every call
 * comes from the render thread (frames from {@code RenderFrameEvent}, the drain from the client
 * tick), so the counters are plain fields.</p>
 */
final class FrameTimeWindow {

    /** Frame times at or above this many milliseconds share the overflow bucket. */
    static final int HISTOGRAM_MS = 250;

    private static final long NS_PER_MS = 1_000_000L;

    /** Everything a window counted, read once at its end. Percentiles are in milliseconds. */
    record Snapshot(long frames, long pausedFrames, long sumNs, long maxNs,
                    double p50Ms, double p95Ms, double p99Ms,
                    long over33, long over50, long over100) {}

    /**
     * What the line says about this client besides the counters. {@code cap} is the frame-rate
     * limit as the video settings show it ("max" when unlimited); {@code focused} whether the window
     * had focus at the window's end (DT's {@code FramerateThrottle} caps an unfocused client, so a
     * low fps there is the throttle, not lag); {@code screen} the open screen's simple class name,
     * or "none".
     */
    record Context(long wallNs, String cap, boolean vsync, boolean focused, String screen) {}

    private final long[] buckets = new long[HISTOGRAM_MS + 1];
    private long frames;
    private long pausedFrames;
    private long sumNs;
    private long maxNs;
    private long over33;
    private long over50;
    private long over100;

    /** One frame took {@code ns}; {@code paused} = the game was paused while it drew. */
    void record(long ns, boolean paused) {
        if (ns <= 0) return;
        long ms = ns / NS_PER_MS;
        buckets[(int) Math.min(ms, HISTOGRAM_MS)]++;
        frames++;
        if (paused) pausedFrames++;
        sumNs += ns;
        if (ns > maxNs) maxNs = ns;
        if (ns >= 33 * NS_PER_MS) over33++;
        if (ns >= 50 * NS_PER_MS) over50++;
        if (ns >= 100 * NS_PER_MS) over100++;
    }

    /** Reads the window and resets it for the next one. */
    Snapshot drain() {
        Snapshot s = new Snapshot(frames, pausedFrames, sumNs, maxNs,
            percentileMs(0.50), percentileMs(0.95), percentileMs(0.99),
            over33, over50, over100);
        Arrays.fill(buckets, 0);
        frames = 0;
        pausedFrames = 0;
        sumNs = 0;
        maxNs = 0;
        over33 = 0;
        over50 = 0;
        over100 = 0;
        return s;
    }

    /**
     * The frame time {@code q} of frames were at or under, as the upper edge of its 1 ms bucket
     * (never above the window's true maximum). The overflow bucket reports the maximum itself.
     */
    private double percentileMs(double q) {
        if (frames == 0) return 0;
        long rank = (long) Math.ceil(q * frames);
        long seen = 0;
        double maxMs = maxNs / (double) NS_PER_MS;
        for (int i = 0; i < buckets.length; i++) {
            seen += buckets[i];
            if (seen >= rank) {
                return i == HISTOGRAM_MS ? maxMs : Math.min(i + 1, maxMs);
            }
        }
        return maxMs;
    }

    /** The {@code [frame]} line for one window. */
    static String format(Snapshot s, Context c) {
        double fps = c.wallNs() <= 0 ? 0 : s.frames() * 1e9 / c.wallNs();
        return "[frame] fps=" + f1(fps)
            + " cap=" + c.cap() + " vsync=" + (c.vsync() ? "on" : "off")
            + " frames=" + s.frames()
            + " ms avg=" + f1(s.frames() == 0 ? 0 : s.sumNs() / (double) s.frames() / NS_PER_MS)
            + " p50=" + f1(s.p50Ms()) + " p95=" + f1(s.p95Ms()) + " p99=" + f1(s.p99Ms())
            + " max=" + f1(s.maxNs() / (double) NS_PER_MS)
            + " over33=" + s.over33() + " over50=" + s.over50() + " over100=" + s.over100()
            + " paused=" + s.pausedFrames()
            + " focused=" + (c.focused() ? "yes" : "no")
            + " screen=" + c.screen();
    }

    private static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
