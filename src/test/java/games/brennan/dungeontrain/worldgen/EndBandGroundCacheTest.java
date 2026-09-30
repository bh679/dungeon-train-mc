package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared ground cache's sharing, trimming and failure rules, without a Minecraft chunk. */
class EndBandGroundCacheTest {

    private static EndBandGroundCache.Key key(long chunk) {
        return new EndBandGroundCache.Key(CycleLayout.Style.BETTER, chunk);
    }

    @Test
    @DisplayName("a key is generated once and served from the cache after that")
    void generatesOnce() {
        EndBandGroundCache<String> cache = new EndBandGroundCache<>(8);
        AtomicInteger runs = new AtomicInteger();
        assertEquals("g1", cache.get(key(1), () -> "g" + runs.incrementAndGet()));
        assertEquals("g1", cache.get(key(1), () -> "g" + runs.incrementAndGet()));
        assertEquals(1, runs.get());
        assertTrue(cache.contains(key(1)));
    }

    @Test
    @DisplayName("a BoP pass and a BetterEnd pass at the same End chunk are different keys")
    void styleIsPartOfTheKey() {
        EndBandGroundCache<String> cache = new EndBandGroundCache<>(8);
        cache.get(new EndBandGroundCache.Key(CycleLayout.Style.BETTER, 5), () -> "better");
        assertEquals("bop", cache.get(new EndBandGroundCache.Key(CycleLayout.Style.BOP, 5), () -> "bop"));
        assertEquals(2, cache.size());
    }

    @Test
    @DisplayName("a second thread asking for a key being generated waits for that result instead of generating again")
    void secondThreadWaitsForTheOwner() throws InterruptedException {
        EndBandGroundCache<String> cache = new EndBandGroundCache<>(8);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        Thread owner = new Thread(() -> cache.get(key(7), () -> {
            runs.incrementAndGet();
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "owner";
        }));
        owner.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        AtomicReference<String> seen = new AtomicReference<>();
        Thread waiter = new Thread(() -> seen.set(cache.get(key(7), () -> {
            runs.incrementAndGet();
            return "waiter";
        })));
        waiter.start();
        release.countDown();
        owner.join(5000);
        waiter.join(5000);
        assertEquals("owner", seen.get());
        assertEquals(1, runs.get());
    }

    @Test
    @DisplayName("past the cap the least recently used finished entries go, never one still being generated")
    void trimsLeastRecentlyUsedFinishedEntries() throws InterruptedException {
        EndBandGroundCache<String> cache = new EndBandGroundCache<>(2);
        cache.get(key(1), () -> "a");
        cache.get(key(2), () -> "b");
        cache.get(key(1), () -> "unused");          // touch 1: 2 is now the oldest
        cache.get(key(3), () -> "c");
        assertEquals(2, cache.size());
        assertTrue(cache.contains(key(1)));
        assertFalse(cache.contains(key(2)));
        assertTrue(cache.contains(key(3)));

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread owner = new Thread(() -> cache.get(key(4), () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "d";
        }));
        owner.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        cache.get(key(5), () -> "e");
        cache.get(key(6), () -> "f");
        assertTrue(cache.contains(key(4)), "an entry still generating is never trimmed");
        release.countDown();
        owner.join(5000);
    }

    @Test
    @DisplayName("a generation that fails or yields nothing releases the key")
    void failuresReleaseTheKey() {
        EndBandGroundCache<String> cache = new EndBandGroundCache<>(8);
        assertThrows(IllegalStateException.class, () -> cache.get(key(9), () -> {
            throw new IllegalStateException("boom");
        }));
        assertFalse(cache.contains(key(9)));
        assertNull(cache.get(key(10), () -> null));
        assertFalse(cache.contains(key(10)));
        assertEquals("ok", cache.get(key(9), () -> "ok"));
    }

    @Test
    @DisplayName("clear drops everything")
    void clearDropsEverything() {
        EndBandGroundCache<String> cache = new EndBandGroundCache<>(8);
        cache.get(key(1), () -> "a");
        cache.clear();
        assertEquals(0, cache.size());
        assertFalse(cache.contains(key(1)));
    }
}
