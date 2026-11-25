package games.brennan.dungeontrain.portal;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * A least-recently-used map bounded by a total weight rather than an entry count — what holds the
 * chunk dimension samples ({@link PortalChunkTerrain}).
 *
 * <p>Weighted because the entries differ by an order of magnitude: a one-chunk room's slice is
 * 8,192 cells and a 3x3's is 73,728. Least-recently-used because a standing room is read every time
 * it is stamped, poured or decorated, so the rooms a player can reach stay young, and what goes when
 * the budget is spent is the ground of pairs the train has long since left. The old bound emptied
 * the whole map at 512 entries, standing rooms included.</p>
 *
 * <p>Every method is synchronized: the sampler thread publishes and the server thread reads.</p>
 */
final class PortalChunkCache<V> {

    private final long budget;
    private final ToLongFunction<V> weigher;
    private final LinkedHashMap<Integer, V> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long weight;

    PortalChunkCache(long budget, ToLongFunction<V> weigher) {
        if (budget <= 0) throw new IllegalArgumentException("budget must be positive: " + budget);
        this.budget = budget;
        this.weigher = weigher;
    }

    /** The value under {@code key}, which also makes it the most recently used. */
    synchronized V get(int key) {
        return entries.get(key);
    }

    synchronized boolean containsKey(int key) {
        return entries.containsKey(key);
    }

    /**
     * File {@code value} under {@code key}, then drop the least recently used entries until the
     * total weight is back within budget. The entry just put is never dropped, even when it alone
     * is over budget: it is the ground a player is about to walk into.
     */
    synchronized void put(int key, V value) {
        V previous = entries.put(key, value);
        if (previous != null) weight -= weigher.applyAsLong(previous);
        weight += weigher.applyAsLong(value);
        Iterator<Map.Entry<Integer, V>> oldest = entries.entrySet().iterator();
        while (weight > budget && oldest.hasNext()) {
            Map.Entry<Integer, V> entry = oldest.next();
            if (entry.getKey() == key) continue;
            weight -= weigher.applyAsLong(entry.getValue());
            oldest.remove();
        }
    }

    synchronized V remove(int key) {
        V removed = entries.remove(key);
        if (removed != null) weight -= weigher.applyAsLong(removed);
        return removed;
    }

    /** Remove {@code key} only while it still holds {@code expected} (by identity). */
    synchronized boolean remove(int key, V expected) {
        if (entries.get(key) != expected) return false;
        remove(key);
        return true;
    }

    synchronized void clear() {
        entries.clear();
        weight = 0;
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized long weight() {
        return weight;
    }
}
