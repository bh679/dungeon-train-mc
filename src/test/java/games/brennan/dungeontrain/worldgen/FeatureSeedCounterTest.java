package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link FeatureSeedCounter} — BCLib's {@code rotateRight(seed, counter++)} feature-seed
 * rotation with the counter held per thread.
 */
final class FeatureSeedCounterTest {

    private static final long SEED = 0x9E3779B97F4A7C15L;

    @Test
    @DisplayName("sequence is rotateRight(seed, 0..n) — BCLib's single-threaded output")
    void matchesBclibFormula() {
        FeatureSeedCounter.begin();
        FeatureSeedCounter.captureSeed(SEED);
        for (int i = 0; i < 200; i++) {
            assertEquals(Long.rotateRight(SEED, i), FeatureSeedCounter.next());
        }
    }

    @Test
    @DisplayName("begin() restarts the counter for the next decoration call")
    void beginResets() {
        FeatureSeedCounter.begin();
        FeatureSeedCounter.captureSeed(SEED);
        FeatureSeedCounter.next();
        FeatureSeedCounter.next();

        FeatureSeedCounter.begin();
        FeatureSeedCounter.captureSeed(SEED + 1);
        assertEquals(SEED + 1, FeatureSeedCounter.next());
        assertEquals(Long.rotateRight(SEED + 1, 1), FeatureSeedCounter.next());
    }

    @Test
    @DisplayName("two threads decorating in lockstep each keep their own sequence")
    void threadsDoNotShareCounter() throws Exception {
        int steps = 500;
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<String> failure = new AtomicReference<>();

        Runnable worker = () -> {
            long seed = Thread.currentThread().getName().hashCode() * 31L + SEED;
            try {
                barrier.await();
                FeatureSeedCounter.begin();
                barrier.await();
                FeatureSeedCounter.captureSeed(seed);
                for (int i = 0; i < steps; i++) {
                    barrier.await(); // interleave every call with the other thread's
                    long got = FeatureSeedCounter.next();
                    if (got != Long.rotateRight(seed, i)) {
                        failure.compareAndSet(null, Thread.currentThread().getName() + " step " + i);
                    }
                }
            } catch (Exception e) {
                failure.compareAndSet(null, e.toString());
            }
        };
        Thread a = new Thread(worker, "sampler-a");
        Thread b = new Thread(worker, "sampler-b");
        a.start();
        b.start();
        a.join();
        b.join();
        assertNull(failure.get());
    }
}
