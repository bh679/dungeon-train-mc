package games.brennan.dungeontrain.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every per-store cache that a package switch, import or reload must drop, in one place.
 *
 * <p>{@link ThreeTierStore} registers itself on construction; the stores that keep a cache of their
 * own shape ({@code TrackTemplateStore}'s cells, the prefab rosters) register a {@link Runnable}.
 * {@code TemplateStores.clearCaches()} loops {@link #clearAll()} instead of naming each store —
 * the hand-kept list it replaced had already missed five.</p>
 *
 * <p>Registration happens in static initialisers, so a store whose class has not loaded yet is
 * simply not here — and has no cache to clear.</p>
 */
public final class StoreRegistry {

    private static final Map<String, Runnable> CLEARERS = new LinkedHashMap<>();

    private StoreRegistry() {}

    /** Register {@code clearCache} under {@code name}; a repeat name replaces the earlier entry. */
    public static synchronized void register(String name, Runnable clearCache) {
        CLEARERS.put(name, clearCache);
    }

    /** Clear every registered cache, in registration order. */
    public static void clearAll() {
        List<Runnable> snapshot;
        synchronized (StoreRegistry.class) {
            snapshot = new ArrayList<>(CLEARERS.values());
        }
        for (Runnable r : snapshot) r.run();
    }

    /** Registered names, in order — for tests and the debug command. */
    public static synchronized List<String> names() {
        return List.copyOf(CLEARERS.keySet());
    }
}
