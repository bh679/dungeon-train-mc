package games.brennan.dungeontrain.train;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Keeps {@link WedgedMobEjector}'s log to one line per mob per window. A mob that keeps getting back
 * into the floor would otherwise log every time it is moved; its repeats are counted and reported on
 * the next line that does print.
 *
 * <p>Keyed by entity id and game time so it can be unit-tested without a game bootstrap. Not
 * thread-safe: one per level, used from the server thread.</p>
 */
public final class EjectLogThrottle {

    /** 30 s at 20 TPS. */
    static final long WINDOW_TICKS = 600L;

    /** Prune at most this often, so a busy tick doesn't walk the map every call. */
    private static final long PRUNE_EVERY_TICKS = 200L;

    private static final class Entry {
        long lastLoggedTick;
        int suppressed;
    }

    private final Map<Integer, Entry> entries = new HashMap<>();
    private long lastPruneTick = Long.MIN_VALUE;

    /**
     * Records one ejection.
     *
     * @return {@code -1} when this one should not be logged, otherwise how many ejections of the
     *         same mob went unlogged since its last line (0 for its first)
     */
    public int record(int entityId, long gameTime) {
        prune(gameTime);
        Entry entry = entries.get(entityId);
        if (entry == null) {
            entry = new Entry();
            entry.lastLoggedTick = gameTime;
            entries.put(entityId, entry);
            return 0;
        }
        if (gameTime - entry.lastLoggedTick < WINDOW_TICKS) {
            entry.suppressed++;
            return -1;
        }
        int repeats = entry.suppressed;
        entry.lastLoggedTick = gameTime;
        entry.suppressed = 0;
        return repeats;
    }

    int size() {
        return entries.size();
    }

    /** Drops mobs not logged for two windows: they stopped coming back (or are gone). */
    private void prune(long gameTime) {
        if (lastPruneTick != Long.MIN_VALUE && gameTime - lastPruneTick < PRUNE_EVERY_TICKS) return;
        lastPruneTick = gameTime;
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            if (gameTime - it.next().lastLoggedTick >= 2 * WINDOW_TICKS) it.remove();
        }
    }
}
