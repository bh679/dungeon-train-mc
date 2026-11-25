package games.brennan.dungeontrain.worldgen;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.util.Arrays;

/**
 * Chunks that unloaded without being written this session, with the game time of each one's latest
 * drop. A regenerated chunk asks {@link #wasDropped} so its track is painted again, and a dimensional
 * carriage asks {@link #droppedSince} whether its own blocks were lost after it was stamped.
 *
 * <p>Holds at most {@code cap} chunks: past that, the oldest quarter is forgotten. A forgotten chunk is
 * one dropped long ago; if a player rides back to it, it regenerates without its track re-queued.</p>
 */
final class DropLog {

    private final int cap;
    private final Long2LongOpenHashMap droppedAt = new Long2LongOpenHashMap();
    private long total;

    DropLog(int cap) {
        if (cap < 4) throw new IllegalArgumentException("cap must be at least 4: " + cap);
        this.cap = cap;
    }

    synchronized void record(long key, long gameTime) {
        droppedAt.put(key, gameTime);
        total++;
        if (droppedAt.size() > cap) forgetOldestQuarter();
    }

    synchronized boolean wasDropped(long key) {
        return droppedAt.containsKey(key);
    }

    synchronized boolean droppedSince(long key, long sinceGameTime) {
        return droppedAt.containsKey(key) && droppedAt.get(key) >= sinceGameTime;
    }

    /** Distinct chunks remembered. */
    synchronized int size() { return droppedAt.size(); }

    /** Drops recorded since the last {@link #clear}, counting a chunk again each time it drops. */
    synchronized long total() { return total; }

    synchronized void clear() {
        droppedAt.clear();
        total = 0L;
    }

    private void forgetOldestQuarter() {
        long[] times = droppedAt.values().toLongArray();
        Arrays.sort(times);
        long keepFrom = times[times.length / 4];
        droppedAt.long2LongEntrySet().removeIf(entry -> entry.getLongValue() < keepFrom);
        // Many drops in one tick share a time; never let ties keep the log over its cap.
        if (droppedAt.size() > cap) {
            int excess = droppedAt.size() - cap;
            var it = droppedAt.long2LongEntrySet().iterator();
            while (excess-- > 0 && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }
}
