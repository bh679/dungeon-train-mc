package games.brennan.dungeontrain.portal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The sampled cubes {@link PortalChunkTerrain} holds, bounded by bytes rather than by count, with
 * the least recently used dropped one at a time when it is over budget.
 *
 * <p><b>Pins.</b> A cube a room is still being built from is never dropped: a room that lost its
 * cube mid-build stood as its bare template, or without its trees, its mobs or its sea. A pair is
 * pinned the moment anything asks for its cube ({@link #get}, which pins before it reads, inside the
 * same lock as eviction, so nothing can slip between the two), whether or not the cube is there yet,
 * so the cube the sampler publishes for it is held until it is collected. The server then says once
 * a tick which pairs are still mid-build ({@link #retainPins}); a pin asked for since the last such
 * call survives it, so a planner asking every tick is never let go between two asks.</p>
 *
 * <p>Pins win over the budget: if the pinned cubes alone are over it, the cache stays over it rather
 * than drop one. A pin is a room in progress, and there are only ever a handful of those.</p>
 *
 * <p>Pure Java and thread-safe (one monitor): the sampler thread publishes, the server thread reads.</p>
 */
final class PortalSliceCache<V> {

    private final long budgetBytes;
    /** Oldest use first. Insertion-ordered, with a use moved to the end by hand, so {@link #peek} can read without counting. */
    private final LinkedHashMap<Integer, Entry<V>> entries = new LinkedHashMap<>();
    private final Set<Integer> pinned = new HashSet<>();
    private final Set<Integer> pinnedSinceRetain = new HashSet<>();
    private long totalBytes;

    private record Entry<V>(V value, long bytes) {}

    PortalSliceCache(long budgetBytes) {
        if (budgetBytes <= 0) throw new IllegalArgumentException("budget must be positive: " + budgetBytes);
        this.budgetBytes = budgetBytes;
    }

    /** Pin {@code key}, then read it (counting as a use), or {@code null} when it is not held. */
    synchronized V get(int key) {
        pinned.add(key);
        pinnedSinceRetain.add(key);
        Entry<V> entry = entries.remove(key);
        if (entry == null) return null;
        entries.put(key, entry);
        return entry.value();
    }

    /** Read {@code key} without pinning it or counting a use. */
    synchronized V peek(int key) {
        Entry<V> entry = entries.get(key);
        return entry == null ? null : entry.value();
    }

    synchronized boolean contains(int key) {
        return entries.containsKey(key);
    }

    /**
     * Hold {@code value} under {@code key} (replacing what was there), then drop least recently used
     * unpinned entries, never {@code key} itself, until the total is back inside the budget.
     *
     * @return the keys dropped, oldest first
     */
    synchronized List<Integer> put(int key, V value, long bytes) {
        Entry<V> previous = entries.remove(key);
        entries.put(key, new Entry<>(value, Math.max(0, bytes)));
        if (previous != null) totalBytes -= previous.bytes();
        totalBytes += Math.max(0, bytes);
        return evictToBudget(key);
    }

    private List<Integer> evictToBudget(int keep) {
        if (totalBytes <= budgetBytes) return List.of();
        List<Integer> evicted = new ArrayList<>();
        Iterator<Map.Entry<Integer, Entry<V>>> it = entries.entrySet().iterator();
        while (totalBytes > budgetBytes && it.hasNext()) {
            Map.Entry<Integer, Entry<V>> next = it.next();
            int key = next.getKey();
            if (key == keep || pinned.contains(key)) continue;
            totalBytes -= next.getValue().bytes();
            it.remove();
            evicted.add(key);
        }
        return evicted;
    }

    /** Drop {@code key}'s entry, whatever it holds. Its pin, if any, stays until the next retain. */
    synchronized V remove(int key) {
        Entry<V> entry = entries.remove(key);
        if (entry == null) return null;
        totalBytes -= entry.bytes();
        return entry.value();
    }

    /** Drop {@code key}'s entry only while it is still {@code expected}. */
    synchronized boolean remove(int key, V expected) {
        Entry<V> entry = entries.get(key);
        if (entry == null || entry.value() != expected) return false;
        entries.remove(key);
        totalBytes -= entry.bytes();
        return true;
    }

    /**
     * Keep pinned only {@code keep} and whatever was pinned since the last call; everything else is
     * let go (and may be dropped by the next {@link #put} over budget).
     */
    synchronized void retainPins(Set<Integer> keep) {
        pinned.clear();
        pinned.addAll(keep);
        pinned.addAll(pinnedSinceRetain);
        pinnedSinceRetain.clear();
    }

    synchronized void clear() {
        entries.clear();
        pinned.clear();
        pinnedSinceRetain.clear();
        totalBytes = 0;
    }

    synchronized boolean isPinned(int key) {
        return pinned.contains(key);
    }

    synchronized int pinnedCount() {
        return pinned.size();
    }

    synchronized long bytes() {
        return totalBytes;
    }

    synchronized int size() {
        return entries.size();
    }

    long budget() {
        return budgetBytes;
    }

    /** Whether the total is over budget, which only happens when the pinned entries alone exceed it. */
    synchronized boolean overBudget() {
        return totalBytes > budgetBytes;
    }
}
