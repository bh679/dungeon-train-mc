package games.brennan.dungeontrain.worldgen;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * A small per-thread memo for the Lost City processors' whole-template work.
 *
 * <p>Vanilla runs every processor's {@code finalizeProcessing} over the whole template once per chunk a
 * structure piece touches — a dozen times for a big tower — and each call gets the same lists. So
 * {@link LostCityStretchProcessor}, {@link LostCityBiteProcessor} and {@link LostCityFacadeProcessor} keep
 * what they worked out here, keyed by the processor itself (by identity: a list may hold several of one
 * kind), the placement offset and a fingerprint of the list the work was derived from. The fingerprint
 * also guards against a datapack that pairs these processors with one reading the level, which could hand
 * them a different list per chunk: that simply misses.</p>
 *
 * <p>A few entries per worker thread, least recently used out first; no locking. Pure performance — the
 * processors return the same blocks with or without it ({@code LostCityPlacementMemoTest}).</p>
 */
final class LostCityPlacementMemo {

    /** Entries per thread: one placement's processors (up to four finalize processors) a few times over. */
    private static final int CAPACITY = 16;

    /** Off only in tests, to produce the unmemoised reference. */
    static volatile boolean enabled = true;
    static final AtomicLong HITS = new AtomicLong();
    static final AtomicLong MISSES = new AtomicLong();

    /** {@code owner} compares by identity — processors don't override {@code equals}. */
    record Key(StructureProcessor owner, long offset, long print) {}

    private static final ThreadLocal<Map<Key, Object>> CACHE = ThreadLocal.withInitial(
            () -> new LinkedHashMap<>(CAPACITY * 2, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, Object> eldest) {
                    return size() > CAPACITY;
                }
            });

    private LostCityPlacementMemo() {}

    /** The value for {@code key}, computed (and kept) on a miss. {@code compute} must not return null. */
    @SuppressWarnings("unchecked")
    static <V> V get(Key key, Supplier<V> compute) {
        if (!enabled) return compute.get();
        Map<Key, Object> cache = CACHE.get();
        Object hit = cache.get(key);
        if (hit != null) {
            HITS.incrementAndGet();
            return (V) hit;
        }
        MISSES.incrementAndGet();
        V value = compute.get();
        cache.put(key, value);
        return value;
    }

    /** Drops this thread's entries (tests). */
    static void clear() {
        CACHE.get().clear();
    }

    /**
     * An order-sensitive 64-bit fingerprint of {@code infos}' positions and, with {@code states}, their block
     * states (by identity — block states are interned). One allocation-free pass.
     */
    static long fingerprint(List<StructureTemplate.StructureBlockInfo> infos, boolean states) {
        long h = mix(infos.size() + 0x9E3779B97F4A7C15L);
        for (StructureTemplate.StructureBlockInfo info : infos) {
            h = mix(h ^ info.pos().asLong());
            if (states) h = mix(h ^ System.identityHashCode(info.state()));
        }
        return h;
    }

    /** Stafford's mix13 over a golden-ratio step. */
    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
