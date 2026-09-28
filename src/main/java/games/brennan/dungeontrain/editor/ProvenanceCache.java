package games.brennan.dungeontrain.editor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Memo for {@link UserContentPaths#provenanceOf(String, String)}.
 *
 * <p>The editor overlay rebuilds every plot label and type menu for the stamped category on
 * every server tick, and each entry asks which tier (user / imported / bundled) backs its file.
 * Uncached that answer is a run of filesystem stats plus a directory listing per template —
 * several hundred templates, twice a tick, on the server thread. This class turns the steady
 * state into one map lookup per template.</p>
 *
 * <p>Two invalidation paths, mirroring {@link StageBlockIndex}:</p>
 * <ul>
 *   <li><b>Explicit</b> — {@link #invalidateAll()} from the template stores' write paths and the
 *       {@code TemplateStores} reload barrier, so a save, delete, rename, package switch or import
 *       re-tints on the very next tick.</li>
 *   <li><b>Time-bounded</b> — every entry is dropped once {@link #TTL_NANOS} has elapsed since the
 *       last clear, so a write seam this class does not know about can leave a tint stale for at
 *       most that long, while the stat storm still shrinks from 20 sweeps a second to one every
 *       few seconds.</li>
 * </ul>
 *
 * <p>Thread-safe: the stores are {@code synchronized} statics and the overlay runs on the server
 * thread, but the client-side builder scan reads through here too.</p>
 */
public final class ProvenanceCache {

    /** How long a cached answer may outlive the last clear before the whole map is dropped. */
    static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private static final Map<String, UserContentPaths.Provenance> CACHE = new ConcurrentHashMap<>();

    /** Bumped on every clear — a cheap change signal for per-tick snapshot dedup keys. */
    private static final AtomicLong GENERATION = new AtomicLong();

    private static final AtomicLong LAST_CLEAR_NANOS = new AtomicLong(Long.MIN_VALUE);

    /** Injected for the unit test; production reads the monotonic clock. */
    private static volatile LongSupplier clock = System::nanoTime;

    private ProvenanceCache() {}

    /**
     * The cached tier for {@code <subSlug>/<basenameWithExt>}, computing it through
     * {@code loader} on a miss. The loader receives the same key it was asked for.
     */
    public static UserContentPaths.Provenance get(
        String subSlug, String basenameWithExt, Function<String, UserContentPaths.Provenance> loader
    ) {
        expireIfStale();
        String key = subSlug + '/' + basenameWithExt;
        UserContentPaths.Provenance hit = CACHE.get(key);
        if (hit != null) return hit;
        UserContentPaths.Provenance computed = loader.apply(key);
        if (computed != null) CACHE.put(key, computed);
        return computed;
    }

    /** Monotonic change counter — moves on every clear, explicit or by age. */
    public static long generation() {
        return GENERATION.get();
    }

    /** Drop every cached answer and advance {@link #generation()}. Called by the store write paths. */
    public static void invalidateAll() {
        CACHE.clear();
        LAST_CLEAR_NANOS.set(clock.getAsLong());
        GENERATION.incrementAndGet();
    }

    /** Number of cached entries — exposed for the unit test only. */
    static int size() {
        return CACHE.size();
    }

    /** Swap the clock — unit test only. Also clears, so the new clock owns every timestamp. */
    static void setClockForTest(LongSupplier newClock) {
        clock = newClock == null ? System::nanoTime : newClock;
        invalidateAll();
    }

    private static void expireIfStale() {
        long now = clock.getAsLong();
        long last = LAST_CLEAR_NANOS.get();
        if (last == Long.MIN_VALUE) {
            // First touch: stamp without clearing (nothing is cached yet).
            LAST_CLEAR_NANOS.compareAndSet(Long.MIN_VALUE, now);
            return;
        }
        if (now - last >= TTL_NANOS && LAST_CLEAR_NANOS.compareAndSet(last, now)) {
            CACHE.clear();
            GENERATION.incrementAndGet();
        }
    }
}
