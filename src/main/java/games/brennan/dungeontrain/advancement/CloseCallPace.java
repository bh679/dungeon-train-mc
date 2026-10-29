package games.brennan.dungeontrain.advancement;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * How fast a player is getting somewhere this life, for {@link CloseCallRules}' "under 20 minutes away".
 *
 * <p>A rolling window of {@link Sample}s, one at most every {@link #SAMPLE_GAP_MS}, kept for
 * {@link #WINDOW_MS}. The pace is the change across the window, so a stop for a fight shows up and a
 * sprint long ago does not. No estimate until the window spans {@link #MIN_SPAN_MS} with forward
 * progress: a fresh spawn or a stall never reads as close. A sample from a new life (run distance went
 * down), or one that jumps further than a train could carry anyone in the time (a teleport, an admin
 * offset), restarts the window from itself.</p>
 *
 * <p>Immutable: {@link #with} returns a new pace. Pure; wall-clock times are passed in.</p>
 */
public record CloseCallPace(List<Sample> samples) {

    public static final long SAMPLE_GAP_MS = 60_000L;
    public static final long WINDOW_MS = 10L * 60_000L;
    public static final long MIN_SPAN_MS = 5L * 60_000L;
    /** Faster than this per minute is not riding the train; the window restarts. */
    static final double MAX_CARRIAGES_PER_MIN = 400.0;
    static final double MAX_METRES_PER_MIN = 20_000.0;

    public static final CloseCallPace EMPTY = new CloseCallPace(List.of());

    /** One reading: wall-clock {@code atMs}, travelled carriages and metres this life. */
    public record Sample(long atMs, double carriages, double metres) {}

    /** Which counter an ETA is for. */
    public enum Metric { CARRIAGES, METRES }

    public CloseCallPace {
        samples = List.copyOf(samples);
    }

    /** Whether a reading at {@code nowMs} would be taken (the first, or a minute on from the last). */
    public boolean due(long nowMs) {
        return samples.isEmpty() || nowMs - last().atMs() >= SAMPLE_GAP_MS || nowMs < last().atMs();
    }

    /** This pace with {@code s} added (when {@link #due}), the window trimmed. */
    public CloseCallPace with(Sample s) {
        if (!due(s.atMs())) return this;
        if (!samples.isEmpty() && implausible(last(), s)) return new CloseCallPace(List.of(s));
        List<Sample> next = new ArrayList<>(samples.size() + 1);
        for (Sample old : samples) {
            if (s.atMs() - old.atMs() <= WINDOW_MS) next.add(old);
        }
        next.add(s);
        return new CloseCallPace(next);
    }

    /**
     * Milliseconds until {@code metric} reaches {@code target} at the window's pace; empty when the
     * window is too short, progress is not forward, or the target is already reached.
     */
    public OptionalLong etaMs(Metric metric, double target) {
        if (samples.size() < 2) return OptionalLong.empty();
        Sample first = samples.get(0);
        Sample last = last();
        long span = last.atMs() - first.atMs();
        if (span < MIN_SPAN_MS) return OptionalLong.empty();
        double gained = value(last, metric) - value(first, metric);
        double remaining = target - value(last, metric);
        if (gained <= 0 || remaining <= 0) return OptionalLong.empty();
        return OptionalLong.of((long) Math.ceil(remaining * span / gained));
    }

    private Sample last() {
        return samples.get(samples.size() - 1);
    }

    private static double value(Sample s, Metric metric) {
        return metric == Metric.CARRIAGES ? s.carriages() : s.metres();
    }

    /** A new life (run distance only ever grows within one) or a jump no train makes in the time. */
    static boolean implausible(Sample prev, Sample next) {
        if (next.metres() < prev.metres()) return true;
        double minutes = Math.max(1.0, (next.atMs() - prev.atMs()) / 60_000.0);
        return Math.abs(next.carriages() - prev.carriages()) > MAX_CARRIAGES_PER_MIN * minutes
            || Math.abs(next.metres() - prev.metres()) > MAX_METRES_PER_MIN * minutes;
    }
}
