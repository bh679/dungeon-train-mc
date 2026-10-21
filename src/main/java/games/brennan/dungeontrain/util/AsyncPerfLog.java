package games.brennan.dungeontrain.util;

import org.slf4j.Logger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Publishes the {@link DtLogging#PERF} window summaries ({@code [mspt]} and friends) from a daemon
 * thread instead of the thread that produced them.
 *
 * <p>FML's {@code log4j2.xml} writes every DEBUG line to {@code debug.log} synchronously, with an
 * immediate flush, on the calling thread. For the perf summaries that thread is the server thread,
 * and a 2026-10 sweep of 92 player bug-report logs found the stall watchdog catching it blocked for
 * five seconds and more inside {@code RollingRandomAccessFileManager.flush} under
 * {@code PERF_LOGGER.debug} twenty times (thirteen in one session). The lines themselves are what
 * lag reports are triaged from, so they stay; only the write moves.</p>
 *
 * <p>Callers hand over the logger, the slf4j format and the already-evaluated arguments. The
 * arguments are captured as-is, so a call site must pass values, not live game objects: every
 * current site passes primitives, pre-formatted strings or immutable keys. A bounded queue holds
 * what has not been written yet; when it is full the <b>oldest</b> entry is dropped so the newest
 * window always lands, and the next drained line is preceded by a count of what was lost. The
 * drain thread is a daemon started on first use and calls {@link Logger#debug(String, Object...)}
 * exactly as the caller would have, so the level, logger name and appender routing are
 * unchanged; only the {@code [thread]} column of the line reads {@code DT-Perf-Log}.</p>
 */
public final class AsyncPerfLog {

    /** Thread name the drained lines carry in the log. */
    public static final String THREAD_NAME = "DT-Perf-Log";
    /** Entries held while the file is slow; at one line per 2 s per dimension this is minutes of backlog. */
    static final int DEFAULT_CAPACITY = 256;

    /** One queued line: a logger, its slf4j format and the evaluated arguments. */
    record Entry(Logger logger, String format, Object[] args) {
        void publish() {
            logger.debug(format, args);
        }
    }

    private static final AsyncPerfLog INSTANCE = new AsyncPerfLog(DEFAULT_CAPACITY, Entry::publish);

    private final BlockingQueue<Entry> queue;
    private final Consumer<Entry> sink;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean started = new AtomicBoolean();

    /** Package-private so a test can give it a capacity and a sink that records instead of logging. */
    AsyncPerfLog(int capacity, Consumer<Entry> sink) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive: " + capacity);
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.sink = sink;
    }

    /**
     * Queue one DEBUG line for {@code logger}. Never blocks and never touches the appender on this
     * thread. Arguments are captured by reference: pass values, not game objects.
     */
    public static void debug(Logger logger, String format, Object... args) {
        INSTANCE.submit(new Entry(logger, format, args));
        INSTANCE.ensureStarted();
    }

    /** Lines dropped so far because the queue was full; for tests and the dropped-count line. */
    public static long droppedCount() {
        return INSTANCE.dropped.get();
    }

    /** Enqueue, discarding the oldest entry if the queue is full. */
    void submit(Entry entry) {
        while (!queue.offer(entry)) {
            if (queue.poll() != null) dropped.incrementAndGet();
        }
    }

    /**
     * Hand the next entry to the sink, blocking until one arrives. When lines were dropped since
     * the previous drain, a one-line notice on the same logger goes first.
     */
    void drainOnce() throws InterruptedException {
        Entry entry = queue.take();
        long lost = dropped.getAndSet(0);
        if (lost > 0) {
            sink.accept(new Entry(entry.logger(), "[perf.log] dropped {} queued line(s) while the log file was slow", new Object[] {lost}));
        }
        sink.accept(entry);
    }

    /** Whether anything is still waiting to be written; for tests. */
    boolean isEmpty() {
        return queue.isEmpty();
    }

    private void ensureStarted() {
        if (started.get() || !started.compareAndSet(false, true)) return;
        Thread drain = new Thread(this::runLoop, THREAD_NAME);
        drain.setDaemon(true);
        drain.start();
    }

    private void runLoop() {
        while (true) {
            try {
                drainOnce();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // A broken appender must not kill the drain thread; the next line gets its own try.
            }
        }
    }
}
