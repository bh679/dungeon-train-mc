package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down {@link RecentStartsQueue} behaviour:
 * <ul>
 *   <li>Push appends to the tail and respects {@code maxSize}.</li>
 *   <li>Push of an already-present basename is a move-to-tail, not a grow.</li>
 *   <li>Oldest entries evict first when {@code size > maxSize}.</li>
 *   <li>{@code maxSize <= 0} clears the queue and drops the push.</li>
 *   <li>Shrinking {@code maxSize} between pushes evicts overflow on the next push.</li>
 *   <li>{@code load} → {@code snapshot} round-trips contents in order.</li>
 * </ul>
 */
final class RecentStartsQueueTest {

    @Test
    @DisplayName("Push adds entries up to maxSize")
    void pushAddsUpToMaxSize() {
        RecentStartsQueue q = new RecentStartsQueue();
        q.push("a", 3);
        q.push("b", 3);
        q.push("c", 3);
        assertEquals(List.of("a", "b", "c"), q.snapshot());
        assertEquals(3, q.size());
    }

    @Test
    @DisplayName("Oldest entry evicted when push exceeds maxSize")
    void oldestEvictedOnOverflow() {
        RecentStartsQueue q = new RecentStartsQueue();
        q.push("a", 2);
        q.push("b", 2);
        q.push("c", 2); // evicts "a"
        assertEquals(List.of("b", "c"), q.snapshot());
        assertFalse(q.contains("a"));
        assertTrue(q.contains("b"));
        assertTrue(q.contains("c"));
    }

    @Test
    @DisplayName("Push of present basename is move-to-tail, not grow")
    void duplicatePushMovesToTail() {
        RecentStartsQueue q = new RecentStartsQueue();
        q.push("a", 3);
        q.push("b", 3);
        q.push("c", 3);
        q.push("a", 3); // a moves from head to tail; size stays 3
        assertEquals(List.of("b", "c", "a"), q.snapshot());
        assertEquals(3, q.size());
    }

    @Test
    @DisplayName("maxSize = 0 clears and drops")
    void maxSizeZeroIsNoOp() {
        RecentStartsQueue q = new RecentStartsQueue();
        q.push("a", 2);
        q.push("b", 2);
        q.push("c", 0); // clears the queue, "c" is not retained
        assertEquals(List.of(), q.snapshot());
        assertEquals(0, q.size());
        assertFalse(q.contains("c"));
    }

    @Test
    @DisplayName("Shrinking maxSize evicts overflow on next push")
    void shrinkEvictsOnNextPush() {
        RecentStartsQueue q = new RecentStartsQueue();
        q.push("a", 4);
        q.push("b", 4);
        q.push("c", 4);
        q.push("d", 4);
        assertEquals(4, q.size());
        // Next push with maxSize 2 keeps the two most-recent (c, d) and then
        // adds e at the tail, evicting back down to 2.
        q.push("e", 2);
        assertEquals(List.of("d", "e"), q.snapshot());
    }

    @Test
    @DisplayName("Load replaces contents and preserves order")
    void loadReplacesAndPreservesOrder() {
        RecentStartsQueue q = new RecentStartsQueue();
        q.push("x", 4);
        q.load(List.of("a", "b", "c"));
        assertEquals(List.of("a", "b", "c"), q.snapshot());
        assertFalse(q.contains("x"));
    }

    @Test
    @DisplayName("Load null is treated as empty")
    void loadNullIsEmpty() {
        RecentStartsQueue q = new RecentStartsQueue();
        q.push("a", 2);
        q.load(null);
        assertEquals(List.of(), q.snapshot());
    }

    @Test
    @DisplayName("Clear empties the queue")
    void clearEmptiesQueue() {
        RecentStartsQueue q = new RecentStartsQueue();
        q.push("a", 2);
        q.push("b", 2);
        q.clear();
        assertEquals(0, q.size());
        assertEquals(List.of(), q.snapshot());
    }

    @Test
    @DisplayName("Snapshot is detached from underlying queue")
    void snapshotIsDetached() {
        RecentStartsQueue q = new RecentStartsQueue();
        q.push("a", 3);
        q.push("b", 3);
        List<String> snap = q.snapshot();
        q.push("c", 3);
        // Snapshot should not reflect later mutations.
        assertEquals(List.of("a", "b"), snap);
    }
}
