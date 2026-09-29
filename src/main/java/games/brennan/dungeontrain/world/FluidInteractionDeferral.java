package games.brennan.dungeontrain.world;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

/**
 * Fluid-interaction checks that were put off because a neighbour chunk was not loaded.
 *
 * <p>NeoForge's {@code FluidInteractionRegistry.canInteract(level, pos)} asks every registered
 * interaction predicate about each of the six face neighbours of a liquid block, and the built-in
 * predicates read them straight through the {@code ServerLevel} — which, for a neighbour in a chunk
 * that is not loaded, is a <b>synchronous chunk generation on the server thread</b>. A fluid tick in
 * {@code LevelChunk.postProcessGeneration} at a chunk border did exactly that in player logs (#1450,
 * item 2): 5–60&nbsp;s stalls, recursively, as each generated chunk's own post-processing reached
 * into the next unloaded one.</p>
 *
 * <p>{@code FluidInteractionNoLoadMixin} answers "no interaction" for those calls and records the
 * position here instead. {@code FluidInteractionDeferralEvents} drains the store from the level
 * tick and re-runs {@code canInteract} once every missing chunk is loaded, so the interaction
 * (obsidian, cobblestone, basalt…) still happens — later, without forcing the chunk.</p>
 *
 * <p>This class is Minecraft-free on purpose (unit-tested without a bootstrap): positions and
 * chunks are the packed longs of {@code BlockPos.asLong} / {@code ChunkPos.asLong}, and "is this
 * chunk loaded" is a predicate the caller supplies. One instance per {@code ServerLevel}; only ever
 * touched from that level's server thread, so it is deliberately unsynchronised.</p>
 *
 * <p>Bounded two ways: at most {@link #MAX_PENDING} positions (new ones are dropped when full, an
 * abnormal state a stalled or runaway generator would produce), and an entry older than
 * {@link #MAX_AGE_TICKS} is dropped unreplayed — the train has moved on and that chunk is not coming.</p>
 */
public final class FluidInteractionDeferral {

    /** Hard cap on pending positions. A few hundred is the realistic peak at a generation front. */
    public static final int MAX_PENDING = 4096;

    /** Five minutes of game time; a neighbour chunk that has not loaded by then belongs to a stretch the train left. */
    public static final long MAX_AGE_TICKS = 5 * 60 * 20;

    /** One deferred check: the chunks that must be loaded first and when it was recorded. */
    private record Entry(long[] missingChunks, long enqueuedTick) {

        boolean isReady(LongPredicate chunkLoaded) {
            for (long key : missingChunks) {
                if (!chunkLoaded.test(key)) return false;
            }
            return true;
        }

        boolean isExpired(long nowTick) {
            return nowTick - enqueuedTick > MAX_AGE_TICKS;
        }
    }

    /** Insertion-ordered so replay happens in defer order and the cap evicts nothing silently. */
    private final Map<Long, Entry> pending = new LinkedHashMap<>();

    // Lifetime counters for the periodic [fluid.defer] log line and the Gate 2 evidence.
    private long deferredTotal;
    private long replayedTotal;
    private long expiredTotal;
    private long droppedTotal;

    /**
     * Record that {@code posLong}'s interaction check is waiting on {@code missingChunks}.
     * Re-deferring a position already pending replaces its entry (the newest missing set wins).
     *
     * @return {@code false} when the store is full and the position was dropped
     */
    public boolean defer(final long posLong, final long[] missingChunks, final long nowTick) {
        if (missingChunks == null || missingChunks.length == 0) {
            throw new IllegalArgumentException("a deferred check must name at least one missing chunk");
        }
        if (!pending.containsKey(posLong) && pending.size() >= MAX_PENDING) {
            droppedTotal++;
            return false;
        }
        pending.put(posLong, new Entry(missingChunks.clone(), nowTick));
        deferredTotal++;
        return true;
    }

    /**
     * Remove and return every position whose missing chunks are all loaded now, in defer order.
     * Entries older than {@link #MAX_AGE_TICKS} are removed without being returned.
     */
    public List<Long> drainReady(final long nowTick, final LongPredicate chunkLoaded) {
        if (pending.isEmpty()) return List.of();
        final List<Long> ready = new ArrayList<>();
        final Iterator<Map.Entry<Long, Entry>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<Long, Entry> e = it.next();
            if (e.getValue().isExpired(nowTick)) {
                it.remove();
                expiredTotal++;
            } else if (e.getValue().isReady(chunkLoaded)) {
                it.remove();
                ready.add(e.getKey());
            }
        }
        replayedTotal += ready.size();
        return ready;
    }

    public int size() {
        return pending.size();
    }

    public long deferredTotal() {
        return deferredTotal;
    }

    public long replayedTotal() {
        return replayedTotal;
    }

    public long expiredTotal() {
        return expiredTotal;
    }

    public long droppedTotal() {
        return droppedTotal;
    }

    // ---- pure chunk geometry -----------------------------------------------------------------

    /** Same packing as vanilla {@code ChunkPos.asLong(x, z)}. */
    public static long chunkKey(final int chunkX, final int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    /** Inverse of {@link #chunkKey}: the chunk X. */
    public static int chunkX(final long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    /** Inverse of {@link #chunkKey}: the chunk Z. */
    public static int chunkZ(final long key) {
        return (int) ((key >>> 32) & 0xFFFFFFFFL);
    }

    /**
     * The chunks, other than the block's own, that a face neighbour of block {@code (x, ·, z)} can be
     * in. Up and down never leave the column, and a fluid interaction only looks at face neighbours,
     * so a corner block reaches two chunks and never the diagonal. Empty for an interior block —
     * the fast path the mixin takes on almost every call.
     */
    public static long[] borderNeighbourChunks(final int x, final int z) {
        final int cx = x >> 4;
        final int cz = z >> 4;
        final int lx = x & 15;
        final int lz = z & 15;
        final int count = (lx == 0 || lx == 15 ? 1 : 0) + (lz == 0 || lz == 15 ? 1 : 0);
        if (count == 0) return new long[0];
        final long[] out = new long[count];
        int i = 0;
        if (lx == 0) out[i++] = chunkKey(cx - 1, cz);
        if (lx == 15) out[i++] = chunkKey(cx + 1, cz);
        if (lz == 0) out[i++] = chunkKey(cx, cz - 1);
        if (lz == 15) out[i] = chunkKey(cx, cz + 1);
        return out;
    }
}
