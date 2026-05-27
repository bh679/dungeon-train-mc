package games.brennan.dungeontrain.narrative;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Bounded, dedup-on-push FIFO of story basenames. Drives the narrative
 * lectern's "recently started" cooldown — when the world has just cycled
 * through every story, picks are filtered against this queue to avoid
 * starting the same story twice in a row.
 *
 * <p>Owned by {@link NarrativeProgressData}; that owner is responsible for
 * marking the SavedData dirty on mutation and for persisting the queue to
 * NBT. This class is pure Java with no Minecraft dependency so it can be
 * unit-tested without a {@code ServerLevel} fixture.</p>
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>{@link #push} adds to the tail (most-recent end). If the basename is
 *       already present, the existing slot is removed first — net effect is
 *       move-to-tail, no growth on duplicates.</li>
 *   <li>After each push, oldest entries are evicted until
 *       {@code size <= maxSize}.</li>
 *   <li>{@code maxSize <= 0} is treated as "disabled" — the queue is
 *       cleared and the push is dropped. Handles the
 *       {@code totalNarratives < 2} edge case where the cooldown window
 *       would round down to zero.</li>
 * </ul>
 */
public final class RecentStartsQueue {

    private final Deque<String> queue = new ArrayDeque<>();

    /**
     * Push {@code basename} to the tail of the queue. Removes any existing
     * occurrence first (dedup → move-to-tail). After insertion, evicts from
     * the head until the queue's size is at most {@code maxSize}. When
     * {@code maxSize <= 0}, the queue is cleared and the push is dropped.
     */
    public void push(String basename, int maxSize) {
        if (maxSize <= 0) {
            queue.clear();
            return;
        }
        queue.remove(basename);
        queue.addLast(basename);
        while (queue.size() > maxSize) {
            queue.removeFirst();
        }
    }

    /** True if {@code basename} is currently in the queue. */
    public boolean contains(String basename) {
        return queue.contains(basename);
    }

    /**
     * Snapshot of the queue, oldest-first. Returned list is unmodifiable
     * and detached from the underlying queue.
     */
    public List<String> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(queue));
    }

    /** Clear the queue. */
    public void clear() {
        queue.clear();
    }

    /**
     * Replace the queue's contents with {@code initial}, preserving the
     * given iteration order as oldest-first. Used by the NBT decode path
     * in {@link NarrativeProgressData}.
     */
    public void load(Collection<String> initial) {
        queue.clear();
        if (initial == null) return;
        for (String name : initial) {
            if (name == null) continue;
            queue.addLast(name);
        }
    }

    /** Current queue length. */
    public int size() {
        return queue.size();
    }
}
