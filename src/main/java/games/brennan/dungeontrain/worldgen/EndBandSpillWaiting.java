package games.brennan.dungeontrain.worldgen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feature spill ({@link EndBandSpill}) waiting for its chunk ({@code endBandFeatureSpill}): held until the
 * target chunk is loaded with its own terrain in, then taken all at once.
 *
 * <p>Held <b>per source</b>: a chunk keeps at most one spill from each neighbour, and a newer spill from the
 * same neighbour — a neighbour re-sampled after unloading before its terrain arrived — replaces the older
 * one instead of piling up beside it. So a chunk never holds more than its eight neighbours' worth.
 * Target chunks are kept oldest-first and trimmed past {@code cap}; spill that is trimmed is simply lost,
 * like any spill for a chunk that never comes back before a restart.</p>
 *
 * <p>Generic in the value so it is unit-tested without Minecraft. Server thread only.</p>
 */
public final class EndBandSpillWaiting<V> {

    /** Enough for a view-distance strip of band chunks. */
    public static final int DEFAULT_CAP = 512;

    private final Map<Long, Map<Long, V>> byTarget;

    public EndBandSpillWaiting(int cap) {
        int max = Math.max(1, cap);
        this.byTarget = new LinkedHashMap<>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Map<Long, V>> eldest) {
                return size() > max;
            }
        };
    }

    /** Hold {@code spill} from chunk {@code source} for chunk {@code target}, replacing any earlier one from that source. */
    public void hold(long target, long source, V spill) {
        byTarget.computeIfAbsent(target, k -> new LinkedHashMap<>(8)).put(source, spill);
    }

    /** Remove and return everything held for {@code target}, oldest source first (empty if nothing). */
    public List<V> take(long target) {
        Map<Long, V> held = byTarget.remove(target);
        return held == null ? List.of() : List.copyOf(held.values());
    }

    public boolean has(long target) {
        return byTarget.containsKey(target);
    }

    /** Target chunks with spill waiting. */
    public int size() {
        return byTarget.size();
    }

    public void clear() {
        byTarget.clear();
    }
}
