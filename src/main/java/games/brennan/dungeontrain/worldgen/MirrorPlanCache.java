package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.UpsideDownMirror.MirrorPlan;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands the upside-down mirror's precomputed writes from the worldgen worker thread (where
 * {@link UpsideDownMirror#compute} runs, at the {@code SPAWN} generation step) to the main thread
 * (where {@code WorldUpsideDownEvents.onChunkLoad} applies them, at {@code ChunkEvent.Load}).
 *
 * <p>Keyed by packed {@link net.minecraft.world.level.ChunkPos} — the mirror is overworld-only, so a
 * chunk position is a unique key. Populated at SPAWN, consumed (removed) at Load.
 *
 * <p><b>Purely an optimization.</b> Correctness never depends on a plan surviving: a Load that misses
 * the cache recomputes the mirror inline (see {@code WorldUpsideDownEvents}), producing identical
 * terrain. So eviction can drop arbitrary entries — a dropped plan just falls back to inline compute.
 * The cap bounds memory if a chunk is computed at SPAWN but never promoted to FULL (aborted/unloaded
 * mid-generation); in normal streaming, SPAWN→FULL is near-immediate and the map stays small.
 *
 * <p>{@link #clear()} is called on overworld unload to avoid a stale plan from a previous world being
 * applied to a same-positioned chunk in a newly loaded world.
 */
public final class MirrorPlanCache {

    /** Defensive upper bound on pending (computed-but-not-yet-applied) plans. */
    private static final int MAX_ENTRIES = 2048;

    private static final ConcurrentHashMap<Long, MirrorPlan> PLANS = new ConcurrentHashMap<>();

    private MirrorPlanCache() {}

    /** Store a precomputed plan for {@code packedChunkPos} (called on the worldgen worker thread). */
    public static void put(long packedChunkPos, MirrorPlan plan) {
        PLANS.put(packedChunkPos, plan);
        if (PLANS.size() > MAX_ENTRIES) {
            // Over cap → evict arbitrary entries (weakly-consistent iterator). Eviction is safe:
            // an evicted chunk simply recomputes its mirror inline at Load.
            Iterator<Long> it = PLANS.keySet().iterator();
            while (PLANS.size() > MAX_ENTRIES && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    /** Take and remove the plan for {@code packedChunkPos}, or {@code null} if absent (main thread). */
    public static MirrorPlan remove(long packedChunkPos) {
        return PLANS.remove(packedChunkPos);
    }

    /** Drop all pending plans (on overworld unload / server stop). */
    public static void clear() {
        PLANS.clear();
    }
}
