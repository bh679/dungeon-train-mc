package games.brennan.dungeontrain.util;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Collapses a recurring log line into at most one summary per interval — for call sites that fire
 * per carriage, per tile or per chunk, where a line each time is the cost (NeoForge's
 * {@code debug.log} appender writes synchronously on the calling thread; a 2026-09 lag report had
 * the server thread stalled 6 s inside it).
 *
 * <p>{@link #record()} counts one occurrence and, when the interval since the last emit has
 * elapsed, returns how many occurrences the caller's line should summarise; otherwise it returns
 * empty and the caller logs nothing. Lock-free and safe from any thread (worldgen workers call it):
 * exactly one caller wins each window, and no occurrence is lost from the counts. Complements
 * {@link LogFirstN}, which goes silent for good instead of summarising.</p>
 */
public final class ThrottledLog {

    private final long intervalMs;
    private final LongSupplier clockMs;
    private final AtomicLong count = new AtomicLong();
    /** Clock value of the last emit; {@link Long#MIN_VALUE} until the first, so the first call emits. */
    private final AtomicLong lastEmitMs = new AtomicLong(Long.MIN_VALUE);

    public ThrottledLog(long intervalMs) {
        this(intervalMs, System::currentTimeMillis);
    }

    public ThrottledLog(long intervalMs, LongSupplier clockMs) {
        if (intervalMs <= 0) throw new IllegalArgumentException("intervalMs must be positive: " + intervalMs);
        this.intervalMs = intervalMs;
        this.clockMs = clockMs;
    }

    /**
     * Count one occurrence. Returns the occurrences since the previous emit (this one included)
     * when this call should log, else empty.
     */
    public OptionalLong record() {
        count.incrementAndGet();
        long now = clockMs.getAsLong();
        long last = lastEmitMs.get();
        if (last != Long.MIN_VALUE && now - last < intervalMs) return OptionalLong.empty();
        if (!lastEmitMs.compareAndSet(last, now)) return OptionalLong.empty();
        return OptionalLong.of(count.getAndSet(0));
    }

    /** The interval in whole seconds, for the "(N in last Xs)" suffix callers print. */
    public long intervalSeconds() {
        return Math.max(1, intervalMs / 1000);
    }
}
