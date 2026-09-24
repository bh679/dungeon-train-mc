package games.brennan.dungeontrain.editor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The decoration half of {@link EditorPlotSnapshots}' baselines: one fingerprint per plot, taken
 * only while the plot's entities can actually be seen.
 *
 * <p>Why "can be seen" matters: an entity query only returns entities from chunks whose entity
 * sections are accessible. A background stamp ({@link EditorStampQueue}) spawns a plot's mobs into a
 * chunk that is far from the player and not yet entity-visible, so a fingerprint taken there counted
 * nothing — and the plot then read "unsaved" the moment the player walked up and its mobs appeared.
 * The reverse happened too: a correct baseline read "unsaved" once the chunk unloaded and the mobs
 * vanished from the query. Every contents plot with a mob or an armor stand was reported as edited
 * after a category stamp, which is what switched the Save-as guard off.</p>
 *
 * <p>So a plot that cannot be seen is never judged, and a baseline that could not be taken is held
 * <em>pending</em> and taken the first time the plot can be seen — by {@link EditorPlotSnapshots}'
 * tick, which gets there at simulation distance, long before a player is close enough to edit it.</p>
 *
 * <p>Pure over an "observable" flag and a fingerprint supplier, so it is tested without a world.</p>
 *
 * @param <R> what a pending entry needs to be resolved later — the plot's region
 */
final class DecorBaselines<R> {

    private final Map<String, Long> baselines = new HashMap<>();
    private final Map<String, R> pending = new LinkedHashMap<>();

    /** Record {@code key}'s baseline now when observable, else hold {@code region} until it is. */
    void capture(String key, R region, boolean observable, LongSupplier fingerprint) {
        if (observable) {
            baselines.put(key, fingerprint.getAsLong());
            pending.remove(key);
        } else {
            baselines.remove(key);
            pending.put(key, region);
        }
    }

    /**
     * Whether {@code key}'s decoration still matches its baseline. {@code true} when it cannot be
     * judged: the plot is not observable, or there is no baseline. A pending baseline seen for the
     * first time is taken here and matches.
     */
    boolean matches(String key, boolean observable, LongSupplier fingerprint) {
        if (!observable) return true;
        if (pending.containsKey(key)) {
            resolve(key, fingerprint.getAsLong());
            return true;
        }
        Long baseline = baselines.get(key);
        return baseline == null || baseline == fingerprint.getAsLong();
    }

    /** Take the pending baseline for {@code key}. */
    void resolve(String key, long fingerprint) {
        pending.remove(key);
        baselines.put(key, fingerprint);
    }

    /** The plots still waiting for a baseline, with their regions — a copy. */
    Map<String, R> pending() {
        return new LinkedHashMap<>(pending);
    }

    boolean hasPending() {
        return !pending.isEmpty();
    }

    void clear(String key) {
        baselines.remove(key);
        pending.remove(key);
    }

    void clearAll() {
        baselines.clear();
        pending.clear();
    }
}
