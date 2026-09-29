package games.brennan.dungeontrain.util;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link ThrottledLog}'s once-per-interval summary gate, on a fake clock. */
class ThrottledLogTest {

    @Test
    void firstOccurrenceEmitsImmediately() {
        ThrottledLog log = new ThrottledLog(1000, () -> 0L);
        assertEquals(OptionalLong.of(1), log.record());
    }

    @Test
    void occurrencesInsideIntervalAreSuppressedThenSummarised() {
        AtomicLong now = new AtomicLong(5_000);
        ThrottledLog log = new ThrottledLog(1000, now::get);
        assertTrue(log.record().isPresent());

        now.set(5_100);
        assertFalse(log.record().isPresent());
        now.set(5_999);
        assertFalse(log.record().isPresent());

        now.set(6_000);
        assertEquals(OptionalLong.of(3), log.record(), "two suppressed + this one");
        assertFalse(log.record().isPresent(), "a fresh window starts at the emit");
    }

    @Test
    void intervalSecondsRoundsDownButNeverZero() {
        assertEquals(10, new ThrottledLog(10_000).intervalSeconds());
        assertEquals(1, new ThrottledLog(250).intervalSeconds());
    }

    @Test
    void rejectsNonPositiveInterval() {
        assertThrows(IllegalArgumentException.class, () -> new ThrottledLog(0));
    }

    @Test
    void concurrentCallersGetOneEmitPerWindowAndLoseNoCounts() throws InterruptedException {
        AtomicLong now = new AtomicLong(0);
        ThrottledLog log = new ThrottledLog(1000, now::get);
        log.record(); // open the first window

        int threads = 8;
        int perThread = 10_000;
        AtomicLong emits = new AtomicLong();
        AtomicLong summarised = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        now.set(2_000); // every call below falls after the interval; only one may win it
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    log.record().ifPresent(n -> {
                        emits.incrementAndGet();
                        summarised.addAndGet(n);
                    });
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, emits.get(), "exactly one caller wins the window");
        now.set(4_000);
        long rest = log.record().orElseThrow();
        assertEquals((long) threads * perThread + 1, summarised.get() + rest, "no occurrence lost");
    }
}
