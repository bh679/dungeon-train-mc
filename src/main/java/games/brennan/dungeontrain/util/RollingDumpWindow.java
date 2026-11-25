package games.brennan.dungeontrain.util;

/**
 * When the last few short stall dumps were written, so a watchdog can cap them per rolling hour
 * instead of per session.
 *
 * <p>Both stall watchdogs used to stop dumping short stalls for good after ten. A player world
 * left open for twelve hours spent those ten early, and the 1 to 1.4 s stalls of a Lag report of
 * 24 Nov 2026 (autosaves, a carriage spawn) reached the log with no stack at all. Ten an hour
 * keeps the same burst ceiling (about 100 KB, well inside the 512 KB log tail a bug report
 * attaches) and never goes blind.</p>
 *
 * <p>Owned by one sampler thread; not thread-safe. Minecraft-free, unit-tested.</p>
 */
public final class RollingDumpWindow {

    /** How far back a dump still counts against the budget. */
    public static final long WINDOW_MS = 60L * 60L * 1000L;

    private final long[] stamps;
    private int next;
    private int size;

    public RollingDumpWindow(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive: " + capacity);
        this.stamps = new long[capacity];
    }

    /** Records a dump written at {@code now}; the oldest record drops out once the ring is full. */
    public void record(long now) {
        stamps[next] = now;
        next = (next + 1) % stamps.length;
        if (size < stamps.length) size++;
    }

    /** Dumps written in the hour up to {@code now}. */
    public int countWithin(long now) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            if (now - stamps[i] < WINDOW_MS) count++;
        }
        return count;
    }

    /**
     * When the budget next frees a slot: the oldest dump in the window plus an hour. Only
     * meaningful while {@link #countWithin} is at capacity; {@code now} otherwise.
     */
    public long reopensAt(long now) {
        long oldest = Long.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            if (now - stamps[i] < WINDOW_MS) oldest = Math.min(oldest, stamps[i]);
        }
        return oldest == Long.MAX_VALUE ? now : oldest + WINDOW_MS;
    }
}
