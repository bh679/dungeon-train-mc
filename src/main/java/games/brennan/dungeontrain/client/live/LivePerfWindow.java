package games.brennan.dungeontrain.client.live;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * One window of Live Feed counters for the {@code [live]} perf line ({@link LivePerfStats}): frame
 * grabs, encoder writes, decoded frames, texture uploads. Pure — no Minecraft types — so the window
 * math and the line format are unit-tested on a bare JVM.
 *
 * <p>Counters are bumped from the render thread (grabs, uploads, frames), the encoder writer thread
 * and the decoder reader thread, so they are {@link LongAdder}s and atomic maxima: a few nanoseconds
 * each and no allocation. {@link #drain} reads and resets them for the next window.</p>
 */
final class LivePerfWindow {

    /** Everything a window counted, read once at its end. Times are nanoseconds. */
    record Snapshot(long grabs, long grabNs, long grabMaxNs, long skipped,
                    long encFresh, long encDup,
                    long decoded, long publishNs, long publishMaxNs, long decoderRuns, long stalls,
                    long uploads, long uploadNs, long uploadMaxNs,
                    long worstFrameNs,
                    long audioRenders, long audioRenderNs, long audioRenderMaxNs, long audioMirrored,
                    long audioDecodes, long audioDecodeNs, long audioStalls, long audioBlocked) {}

    /** What the line says about this client's context besides the counters. */
    record Context(boolean streaming, @Nullable String streamSize, @Nullable Double encCpuPct,
                   int sessions, int decodeWidth, @Nullable Double decCpuPct, int fps,
                   @Nullable Audio audio) {

        Context(boolean streaming, @Nullable String streamSize, @Nullable Double encCpuPct,
                int sessions, int decodeWidth, @Nullable Double decCpuPct, int fps) {
            this(streaming, streamSize, encCpuPct, sessions, decodeWidth, decCpuPct, fps, null);
        }
    }

    /** The stream audio mirror's twins at the window's end; absent when the stream has no sound. */
    record Audio(int twinSources, long twinBytes) {}

    private final LongAdder grabs = new LongAdder();
    private final LongAdder grabNs = new LongAdder();
    private final AtomicLong grabMaxNs = new AtomicLong();
    private final LongAdder skipped = new LongAdder();
    private final LongAdder encFresh = new LongAdder();
    private final LongAdder encDup = new LongAdder();
    private final LongAdder decoded = new LongAdder();
    private final LongAdder publishNs = new LongAdder();
    private final AtomicLong publishMaxNs = new AtomicLong();
    private final LongAdder decoderRuns = new LongAdder();
    private final LongAdder stalls = new LongAdder();
    private final LongAdder uploads = new LongAdder();
    private final LongAdder uploadNs = new LongAdder();
    private final AtomicLong uploadMaxNs = new AtomicLong();
    private final AtomicLong worstFrameNs = new AtomicLong();
    private final LongAdder audioRenders = new LongAdder();
    private final LongAdder audioRenderNs = new LongAdder();
    private final AtomicLong audioRenderMaxNs = new AtomicLong();
    private final LongAdder audioMirrored = new LongAdder();
    private final LongAdder audioDecodes = new LongAdder();
    private final LongAdder audioDecodeNs = new LongAdder();
    private final LongAdder audioStalls = new LongAdder();
    private final LongAdder audioBlocked = new LongAdder();

    void grab(long ns) { grabs.increment(); grabNs.add(ns); grabMaxNs.accumulateAndGet(ns, Math::max); }
    void grabSkipped() { skipped.increment(); }
    void encoderWrote(boolean fresh) { (fresh ? encFresh : encDup).increment(); }
    void decoded(long ns) { decoded.increment(); publishNs.add(ns); publishMaxNs.accumulateAndGet(ns, Math::max); }
    void decoderRun() { decoderRuns.increment(); }
    void stall() { stalls.increment(); }
    void upload(long ns) { uploads.increment(); uploadNs.add(ns); uploadMaxNs.accumulateAndGet(ns, Math::max); }
    void frame(long ns) { worstFrameNs.accumulateAndGet(ns, Math::max); }
    void audioRendered(long ns) { audioRenders.increment(); audioRenderNs.add(ns); audioRenderMaxNs.accumulateAndGet(ns, Math::max); }
    void audioMirrored() { audioMirrored.increment(); }
    void audioDecoded(long ns) { audioDecodes.increment(); audioDecodeNs.add(ns); }
    void audioStall() { audioStalls.increment(); }
    void audioBlocked() { audioBlocked.increment(); }

    /** Reads every counter and resets it; a bump racing the reset lands in one window or the next. */
    Snapshot drain() {
        return new Snapshot(grabs.sumThenReset(), grabNs.sumThenReset(), grabMaxNs.getAndSet(0), skipped.sumThenReset(),
            encFresh.sumThenReset(), encDup.sumThenReset(),
            decoded.sumThenReset(), publishNs.sumThenReset(), publishMaxNs.getAndSet(0), decoderRuns.sumThenReset(), stalls.sumThenReset(),
            uploads.sumThenReset(), uploadNs.sumThenReset(), uploadMaxNs.getAndSet(0),
            worstFrameNs.getAndSet(0),
            audioRenders.sumThenReset(), audioRenderNs.sumThenReset(), audioRenderMaxNs.getAndSet(0), audioMirrored.sumThenReset(),
            audioDecodes.sumThenReset(), audioDecodeNs.sumThenReset(), audioStalls.sumThenReset(), audioBlocked.sumThenReset());
    }

    /**
     * CPU use as a percentage of one core between two samples of a process's total CPU time, or null
     * when either sample is missing (some platforms report none) or no wall time passed.
     */
    @Nullable
    static Double cpuPercent(@Nullable Duration before, @Nullable Duration after, long wallNs) {
        if (before == null || after == null || wallNs <= 0) return null;
        long cpu = after.minus(before).toNanos();
        if (cpu < 0) return null;
        return cpu * 100.0 / wallNs;
    }

    /** The {@code [live]} line for one window. */
    static String format(Snapshot s, Context c) {
        StringBuilder b = new StringBuilder("[live] role=");
        boolean viewing = c.sessions() > 0 || s.decoded() > 0;
        // "ended": the window in which the last activity stopped — one trailing line, then silence.
        b.append(c.streaming() && viewing ? "stream+view" : c.streaming() ? "stream" : viewing ? "view"
            : s.grabs() > 0 ? "stream" : "ended");
        if (c.streaming() || s.grabs() > 0) {
            b.append(" size=").append(c.streamSize() == null ? "n/a" : c.streamSize())
                .append(" grabs=").append(s.grabs()).append(" skipped=").append(s.skipped())
                .append(" grabMs avg=").append(ms(avg(s.grabNs(), s.grabs()))).append(" max=").append(ms(s.grabMaxNs()))
                .append(" encFrames=").append(s.encFresh() + s.encDup()).append(" dup=").append(s.encDup())
                .append(" encCpu=").append(pct(c.encCpuPct()));
            if (c.audio() != null) {
                // renderMs is per 10 ms chunk; calls = OpenAL calls replayed onto the mirror this window.
                b.append(" audio renderMs avg=").append(ms(avg(s.audioRenderNs(), s.audioRenders())))
                    .append(" max=").append(ms(s.audioRenderMaxNs()))
                    .append(" calls=").append(s.audioMirrored())
                    .append(" twins=").append(c.audio().twinSources())
                    .append(" twinMB=").append(String.format(Locale.ROOT, "%.1f", c.audio().twinBytes() / 1048576.0))
                    .append(" decodes=").append(s.audioDecodes())
                    .append(" decodeMs=").append(ms(s.audioDecodeNs()))
                    .append(" stalls=").append(s.audioStalls())
                    .append(" blocked=").append(s.audioBlocked());
            }
        }
        if (viewing) {
            if (c.streaming() || s.grabs() > 0) b.append(" |");
            b.append(" view sessions=").append(c.sessions()).append(" width=").append(c.decodeWidth())
                .append(" decoded=").append(s.decoded())
                .append(" publishMs avg=").append(ms(avg(s.publishNs(), s.decoded()))).append(" max=").append(ms(s.publishMaxNs()))
                .append(" stalls=").append(s.stalls())
                .append(" runs=").append(s.decoderRuns())
                .append(" uploadMs avg=").append(ms(avg(s.uploadNs(), s.uploads()))).append(" max=").append(ms(s.uploadMaxNs()))
                .append(" decCpu=").append(pct(c.decCpuPct()));
        }
        b.append(" | fps=").append(c.fps()).append(" worstFrameMs=").append(ms(s.worstFrameNs()));
        return b.toString();
    }

    private static long avg(long sum, long n) {
        return n == 0 ? 0 : sum / n;
    }

    private static String ms(long ns) {
        return String.format(Locale.ROOT, "%.2f", ns / 1_000_000.0);
    }

    private static String pct(@Nullable Double p) {
        return p == null ? "n/a" : String.format(Locale.ROOT, "%.0f%%", p);
    }
}
