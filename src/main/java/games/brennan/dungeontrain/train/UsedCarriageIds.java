package games.brennan.dungeontrain.train;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The relay ids of shared carriages a world has already placed, oldest first — the world's memory of
 * "don't show me that build again". Sent to the relay as the lease exclude-list.
 *
 * <p>Ring-capped so an endless run can't grow the save file without bound. The cap sits comfortably
 * above the number of ids a lease call can carry, so trimming never costs an exclusion that would
 * otherwise have been sent.</p>
 *
 * <p>Not thread-safe: it is owned by the world's saved data and touched from the server thread.</p>
 */
public final class UsedCarriageIds {

    /** How many placed-carriage ids to remember. */
    public static final int MAX_REMEMBERED = 512;

    /** Insertion-ordered so eviction drops the oldest and reads can hand back the newest first. */
    private final Set<Integer> ids = new LinkedHashSet<>();

    /**
     * Record a placed carriage.
     *
     * @return true if this id is new (a repeat changes nothing, so callers can skip marking dirty)
     */
    public boolean add(int id) {
        if (!ids.add(id)) return false;
        while (ids.size() > MAX_REMEMBERED) {
            Iterator<Integer> it = ids.iterator();
            it.next();
            it.remove();
        }
        return true;
    }

    /**
     * The most recently placed ids, newest first, capped at {@code limit}. Newest-first because the
     * relay truncates the exclude-list it accepts, so the ids most likely to come round again — this
     * run's — must survive the cut.
     */
    public List<Integer> recent(int limit) {
        if (limit <= 0) return List.of();
        List<Integer> all = new ArrayList<>(ids);
        Collections.reverse(all);
        return limit >= all.size() ? all : new ArrayList<>(all.subList(0, limit));
    }

    /** Oldest-first snapshot for serialisation. */
    public int[] toIntArray() {
        int[] out = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) out[i++] = id;
        return out;
    }

    /** Restore from a serialised oldest-first array (an empty/absent array yields an empty set). */
    public void loadFrom(int[] serialized) {
        ids.clear();
        if (serialized == null) return;
        for (int id : serialized) add(id);
    }

    public int size() {
        return ids.size();
    }

    public boolean contains(int id) {
        return ids.contains(id);
    }
}
