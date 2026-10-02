package games.brennan.dungeontrain.worldgen;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands a per-chunk precomputed "plan" from the worldgen worker thread (where it is computed at the
 * {@code SPAWN} generation step) to the main thread (where it is applied at {@code ChunkEvent.Load}).
 * Generic sibling of {@link MirrorPlanCache}; same semantics.
 *
 * <p>Keyed by packed {@link net.minecraft.world.level.ChunkPos} — callers are overworld-only, so a chunk
 * position is a unique key. Populated at SPAWN, consumed (removed) at Load.
 *
 * <p><b>Purely an optimization.</b> Correctness never depends on a plan surviving: a Load that misses the
 * cache recomputes inline, producing identical terrain. So eviction can drop arbitrary entries. The cap
 * bounds memory if a chunk is computed at SPAWN but never promoted to FULL (aborted/unloaded
 * mid-generation); in normal streaming SPAWN→FULL is near-immediate and the map stays small.
 *
 * <p>{@link #clear()} must be called on overworld unload so a stale plan from a previous world is never
 * applied to a same-positioned chunk in a newly loaded one.
 *
 * @param <T> the plan type (immutable)
 */
public final class ChunkPlanCache<T> {

    /** Defensive upper bound on pending (computed-but-not-yet-applied) plans. */
    private static final int MAX_ENTRIES = 2048;

    private final ConcurrentHashMap<Long, T> plans = new ConcurrentHashMap<>();

    /** Store a precomputed plan for {@code packedChunkPos} (worldgen worker thread). */
    public void put(long packedChunkPos, T plan) {
        plans.put(packedChunkPos, plan);
        if (plans.size() > MAX_ENTRIES) {
            // Over cap → evict arbitrary entries (weakly-consistent iterator). Safe: an evicted chunk
            // simply recomputes its plan inline at Load.
            Iterator<Long> it = plans.keySet().iterator();
            while (plans.size() > MAX_ENTRIES && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    /** Take and remove the plan for {@code packedChunkPos}, or {@code null} if absent (main thread). */
    public T remove(long packedChunkPos) {
        return plans.remove(packedChunkPos);
    }

    /** Drop all pending plans (overworld unload / server stop). */
    public void clear() {
        plans.clear();
    }

    /** Number of pending plans (diagnostics / tests). */
    public int size() {
        return plans.size();
    }
}
