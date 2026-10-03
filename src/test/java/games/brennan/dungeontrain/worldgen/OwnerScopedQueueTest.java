package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link OwnerScopedQueue} — work handed over from worldgen threads only ever reaches the server that made it. */
class OwnerScopedQueueTest {

    /** Stand-ins for two servers run one after the other in the same JVM. */
    private final Object oldServer = new Object();
    private final Object newServer = new Object();

    @Test
    @DisplayName("items drain to their own owner, oldest first, and are gone afterwards")
    void drainsOwnItemsInOrder() {
        OwnerScopedQueue<String> q = new OwnerScopedQueue<>();
        q.offer(newServer, "a");
        q.offer(newServer, "b");
        List<String> out = new ArrayList<>();
        assertEquals(0, q.drain(newServer, out::add));
        assertEquals(List.of("a", "b"), out);
        assertEquals(0, q.size());
    }

    @Test
    @DisplayName("an old server's late items are dropped and counted, never delivered to the new one")
    void foreignOwnerDropped() {
        OwnerScopedQueue<String> q = new OwnerScopedQueue<>();
        q.offer(oldServer, "late spill");
        List<String> out = new ArrayList<>();
        assertEquals(1, q.drain(newServer, out::add));
        assertEquals(List.of(), out);
        assertEquals(0, q.size());
    }

    @Test
    @DisplayName("mixed owners: only the drainer's items come through, in their order")
    void mixedOwners() {
        OwnerScopedQueue<String> q = new OwnerScopedQueue<>();
        q.offer(oldServer, "old 1");
        q.offer(newServer, "new 1");
        q.offer(oldServer, "old 2");
        q.offer(newServer, "new 2");
        List<String> out = new ArrayList<>();
        assertEquals(2, q.drain(newServer, out::add));
        assertEquals(List.of("new 1", "new 2"), out);
    }

    @Test
    @DisplayName("owners compare by identity, not equals")
    void identityOwners() {
        OwnerScopedQueue<String> q = new OwnerScopedQueue<>();
        q.offer(new String("server"), "x");
        List<String> out = new ArrayList<>();
        assertEquals(1, q.drain(new String("server"), out::add));
        assertEquals(List.of(), out);
    }

    @Test
    @DisplayName("clear empties the queue; draining an empty queue is a no-op")
    void clearAndEmpty() {
        OwnerScopedQueue<String> q = new OwnerScopedQueue<>();
        q.offer(oldServer, "x");
        q.offer(newServer, "y");
        assertEquals(2, q.size());
        q.clear();
        assertEquals(0, q.size());
        List<String> out = new ArrayList<>();
        assertEquals(0, q.drain(newServer, out::add));
        assertEquals(List.of(), out);
    }

    @Test
    @DisplayName("offers from several threads at once all arrive")
    void concurrentOffers() throws InterruptedException {
        OwnerScopedQueue<Integer> q = new OwnerScopedQueue<>();
        int threads = 8, each = 1_000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            int base = t * each;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < each; i++) q.offer(newServer, base + i);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        List<Integer> out = new ArrayList<>();
        assertEquals(0, q.drain(newServer, out::add));
        assertEquals(threads * each, out.size());
        assertEquals(threads * each, out.stream().distinct().count());
    }
}
