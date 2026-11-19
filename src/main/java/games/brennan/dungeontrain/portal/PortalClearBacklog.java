package games.brennan.dungeontrain.portal;

import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

/**
 * Clears waiting for a chunk that was not ready when they were asked for, per chunk column.
 *
 * <p>The bookkeeping half of {@link PortalClear#clearBoxDeferring}; it holds no level and writes no
 * block, so it is unit-testable (see {@code PortalClearBacklogTest}). Readiness is a question the
 * caller answers, with {@code ServerChunkCache.getChunkNow} in production. Server thread only.</p>
 *
 * @param <M> what spares cells during the clear ({@link PortalCorridorMask} in production)
 */
final class PortalClearBacklog<M> {

    /** One waiting clear, wholly inside a single chunk column. */
    record Entry<M>(BoundingBox box, M mask) {}

    private final Map<Long, List<Entry<M>>> byChunk = new HashMap<>();
    /** Visit order for {@link #takeReady}: a chunk that is not ready goes to the back. */
    private final Deque<Long> order = new ArrayDeque<>();

    /** Packed the same way as {@code ChunkPos.toLong}, so production can unpack with it. */
    static long chunkKey(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    boolean isEmpty() {
        return byChunk.isEmpty();
    }

    /** Pending clears across all columns. */
    int size() {
        return byChunk.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Queue a span lying inside one chunk column. A span directly above the last one queued for that
     * column with the same mask extends it, so a walk of a tall box costs one entry per column rather
     * than one per section. The same span queued twice is one clear.
     */
    void add(BoundingBox span, M mask) {
        long key = chunkKey(span.minX() >> 4, span.minZ() >> 4);
        List<Entry<M>> entries = byChunk.get(key);
        if (entries == null) {
            entries = new ArrayList<>();
            byChunk.put(key, entries);
            order.addLast(key);
        }
        if (!entries.isEmpty()) {
            int lastIndex = entries.size() - 1;
            Entry<M> last = entries.get(lastIndex);
            if (last.mask() == mask && sameColumnSpan(last.box(), span)
                && span.minY() >= last.box().minY() && span.minY() <= last.box().maxY() + 1) {
                entries.set(lastIndex, new Entry<>(new BoundingBox(
                    last.box().minX(), last.box().minY(), last.box().minZ(),
                    last.box().maxX(), Math.max(last.box().maxY(), span.maxY()), last.box().maxZ()), mask));
                return;
            }
        }
        entries.add(new Entry<>(span, mask));
    }

    /**
     * Remove and return what is waiting on chunks that have become ready. Asks about at most
     * {@code maxLookups} chunks and takes at most {@code maxChunks} of them; whatever was asked about
     * and left goes to the back, so a chunk that never loads cannot starve one that has.
     */
    List<Entry<M>> takeReady(LongPredicate ready, int maxLookups, int maxChunks) {
        List<Entry<M>> taken = new ArrayList<>();
        int lookups = Math.min(maxLookups, order.size());
        int chunks = 0;
        for (int i = 0; i < lookups && chunks < maxChunks; i++) {
            long key = order.pollFirst();
            if (ready.test(key)) {
                taken.addAll(byChunk.remove(key));
                chunks++;
            } else {
                order.addLast(key);
            }
        }
        return taken;
    }

    /** Remove and return what is waiting on the ready chunks that {@code box} touches. */
    List<Entry<M>> takeReadyTouching(BoundingBox box, LongPredicate ready) {
        List<Entry<M>> taken = new ArrayList<>();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                long key = chunkKey(cx, cz);
                if (byChunk.containsKey(key) && ready.test(key)) {
                    taken.addAll(byChunk.remove(key));
                    order.remove(key);
                }
            }
        }
        return taken;
    }

    private static boolean sameColumnSpan(BoundingBox a, BoundingBox b) {
        return a.minX() == b.minX() && a.maxX() == b.maxX() && a.minZ() == b.minZ() && a.maxZ() == b.maxZ();
    }
}
