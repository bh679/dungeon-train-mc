package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static games.brennan.dungeontrain.worldgen.SamplerPool.Kind.END_BAND;
import static games.brennan.dungeontrain.worldgen.SamplerPool.Kind.SPHERE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SamplerPool} — one shared pool, auto-sized to a quarter of the cores, with one bounded
 * nearest-first queue, shut down with its server and restarted for the next.
 */
class SamplerPoolTest {

    private static final long WAIT_MS = 10_000L;
    private static final Runnable NOTHING = () -> { };

    /** A job that holds its sampler thread until released. */
    private static final class Gate {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Thread> thread = new AtomicReference<>();

        void run() {
            thread.set(Thread.currentThread());
            started.countDown();
            await(release);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(WAIT_MS, TimeUnit.MILLISECONDS), "timed out waiting on a latch");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static SamplerPool pool(int threads, int maxQueued) {
        return new SamplerPool(() -> threads, () -> 4, maxQueued);
    }

    private static EndBandJobQueue.Players playerAt(int cx, int cz) {
        return new EndBandJobQueue.Players(new int[] {cx}, new int[] {cz});
    }

    /** Occupy {@code pool}'s single thread; the returned gate's job has left the queue and is running. */
    private static Gate hold(SamplerPool pool) {
        Gate gate = new Gate();
        assertTrue(pool.submit(SPHERE, -1L, 0, 0, false, gate::run, NOTHING));
        await(gate.started);
        return gate;
    }

    private static void finish(SamplerPool pool, Gate gate) {
        gate.release.countDown();
        pool.close();
        assertTrue(pool.awaitQuiescent(WAIT_MS));
    }

    @Test
    @DisplayName("auto gives a small server a single sampler thread")
    void autoSmallServer() {
        assertEquals(1, SamplerPool.resolveThreads(0, 1));
        assertEquals(1, SamplerPool.resolveThreads(0, 2));
        assertEquals(1, SamplerPool.resolveThreads(0, 4));
        assertEquals(1, SamplerPool.resolveThreads(0, 7));
    }

    @Test
    @DisplayName("auto scales with cores up to the old combined default of four")
    void autoScalesAndCaps() {
        assertEquals(2, SamplerPool.resolveThreads(0, 8));
        assertEquals(3, SamplerPool.resolveThreads(0, 12));
        assertEquals(4, SamplerPool.resolveThreads(0, 16));
        assertEquals(4, SamplerPool.resolveThreads(0, 64));
    }

    @Test
    @DisplayName("a configured count is used as-is")
    void configuredWins() {
        assertEquals(3, SamplerPool.resolveThreads(3, 2));
        assertEquals(8, SamplerPool.resolveThreads(8, 64));
    }

    @Test
    @DisplayName("stopping drops every waiting job: none runs, each is told, and the thread ends")
    void stopDrainsQueue() throws InterruptedException {
        SamplerPool pool = pool(1, 100);
        Gate gate = hold(pool);
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger dropped = new AtomicInteger();
        for (int i = 0; i < 6; i++) {
            boolean droppable = i % 2 == 0;
            assertTrue(pool.submit(droppable ? END_BAND : SPHERE, i, i, 0, droppable,
                    ran::incrementAndGet, dropped::incrementAndGet));
        }
        assertEquals(6, pool.stats().queued());

        assertEquals(6, pool.close());
        assertEquals(6, dropped.get());
        assertEquals(0, pool.stats().queued());
        assertFalse(pool.awaitQuiescent(50), "the job running at stop has not finished yet");

        gate.release.countDown();
        assertTrue(pool.awaitQuiescent(WAIT_MS));
        gate.thread.get().join(WAIT_MS);
        assertFalse(gate.thread.get().isAlive(), "the stopped world's sampler thread is gone");
        assertEquals(0, ran.get());
    }

    @Test
    @DisplayName("a closed pool refuses jobs and starts no thread")
    void closedRefuses() {
        SamplerPool pool = pool(1, 100);
        pool.close();
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger dropped = new AtomicInteger();
        assertFalse(pool.submit(SPHERE, 1L, 0, 0, false, ran::incrementAndGet, dropped::incrementAndGet));
        assertEquals(0, pool.threads());
        assertEquals(0, pool.stats().queued());
        assertEquals(0, ran.get());
        assertEquals(0, dropped.get(), "a refused job is the caller's to release");
        assertTrue(pool.awaitQuiescent(0), "nothing was ever started");
    }

    @Test
    @DisplayName("after a stop the next world gets fresh threads")
    void recreatesAfterStop() {
        SamplerPool pool = pool(1, 100);
        List<String> names = Collections.synchronizedList(new ArrayList<>());
        for (int world = 0; world < 2; world++) {
            pool.open();
            CountDownLatch done = new CountDownLatch(1);
            assertTrue(pool.submit(SPHERE, 1L, 0, 0, false, () -> {
                names.add(Thread.currentThread().getName());
                done.countDown();
            }, NOTHING));
            await(done);
            pool.close();
            assertTrue(pool.awaitQuiescent(WAIT_MS));
        }
        assertEquals(List.of("DungeonTrain-sampler-g1-1", "DungeonTrain-sampler-g2-1"), names);
    }

    @Test
    @DisplayName("the thread count is read again for each world")
    void rereadsConfiguredThreads() {
        AtomicInteger configured = new AtomicInteger(1);
        SamplerPool pool = new SamplerPool(configured::get, () -> 4, 100);
        Gate first = hold(pool);
        assertEquals(1, pool.threads());
        finish(pool, first);
        assertEquals(0, pool.threads());

        configured.set(3);
        pool.open();
        CountDownLatch allStarted = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 3; i++) {
            assertTrue(pool.submit(SPHERE, i, i, 0, false, () -> {
                allStarted.countDown();
                await(release);
            }, NOTHING));
        }
        await(allStarted);                                  // three jobs at once needs three threads
        assertEquals(3, pool.threads());
        release.countDown();
        pool.close();
        assertTrue(pool.awaitQuiescent(WAIT_MS));
    }

    @Test
    @DisplayName("a full queue evicts its furthest droppable job, and turns a further one away")
    void fullQueueEvictsFurthestDroppable() {
        SamplerPool pool = pool(1, 3);
        Gate gate = hold(pool);
        pool.updatePlayers(playerAt(0, 0), 1_000);
        List<Integer> dropped = new ArrayList<>();
        for (int cx : new int[] {10, 20, 30}) {
            assertTrue(pool.submit(END_BAND, cx, cx, 0, true, NOTHING, () -> dropped.add(cx)));
        }
        assertFalse(pool.hasRoom());

        assertTrue(pool.submit(END_BAND, 5, 5, 0, true, NOTHING, () -> dropped.add(5)));
        assertEquals(List.of(30), dropped, "the nearer newcomer takes the furthest job's place");

        assertFalse(pool.submit(END_BAND, 99, 99, 0, true, NOTHING, () -> dropped.add(99)));
        assertEquals(List.of(30), dropped, "a newcomer further than everything waiting is the one turned away");

        assertEquals(new SamplerPool.Stats(3, 3, 1, 1), pool.stats());
        finish(pool, gate);
    }

    @Test
    @DisplayName("a must-keep job takes a droppable one's place, and is never itself thrown away")
    void fullQueueNeverLosesMustKeep() {
        SamplerPool pool = pool(1, 3);
        Gate gate = hold(pool);
        pool.updatePlayers(playerAt(0, 0), 18);
        List<Integer> dropped = new ArrayList<>();
        List<Integer> ran = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch allRan = new CountDownLatch(3);
        for (int cx : new int[] {10, 20, 30}) {
            assertTrue(pool.submit(END_BAND, cx, cx, 0, true, NOTHING, () -> dropped.add(cx)));
        }
        // Far beyond the 18-chunk keep radius, and further than every droppable job: kept all the same.
        for (int cx : new int[] {700, 500, 600}) {
            assertTrue(pool.submit(SPHERE, cx, cx, 0, false, () -> {
                ran.add(cx);
                allRan.countDown();
            }, () -> dropped.add(cx)));
        }
        assertEquals(List.of(30, 20, 10), dropped, "each made room by evicting the furthest droppable job left");

        assertFalse(pool.submit(SPHERE, 800, 800, 0, false, NOTHING, () -> dropped.add(800)),
                "nothing droppable is left to give way: the caller keeps this one for later");
        assertEquals(3, pool.stats().queued());

        gate.release.countDown();
        await(allRan);
        assertEquals(List.of(500, 600, 700), ran, "nearest the player first");
        assertEquals(List.of(30, 20, 10), dropped);
        finish(pool, gate);
    }

    @Test
    @DisplayName("a chunk that unloads takes its own kind's waiting jobs with it")
    void forgetDropsOneChunksJobs() {
        SamplerPool pool = pool(1, 100);
        Gate gate = hold(pool);
        List<String> dropped = new ArrayList<>();
        assertTrue(pool.submit(SPHERE, 1L, 7, 3, false, NOTHING, () -> dropped.add("sphere-a")));
        assertTrue(pool.submit(SPHERE, 2L, 7, 3, false, NOTHING, () -> dropped.add("sphere-b")));
        assertTrue(pool.submit(SPHERE, 3L, 8, 3, false, NOTHING, () -> dropped.add("other-chunk")));
        assertTrue(pool.submit(END_BAND, 1L, 7, 3, true, NOTHING, () -> dropped.add("end-band")));

        pool.forget(SPHERE, 7, 3);
        assertEquals(List.of("sphere-a", "sphere-b"), dropped);
        assertEquals(2, pool.stats().queued());

        pool.cancel(END_BAND);
        assertEquals(List.of("sphere-a", "sphere-b", "end-band"), dropped);
        assertEquals(1, pool.stats().queued());
        finish(pool, gate);
    }

    @Test
    @DisplayName("every queued job runs though wake-up tokens are thrown away when the token queue is full")
    void noJobIsStranded() {
        SamplerPool pool = pool(2, 1_000);
        int jobs = 500;
        CountDownLatch done = new CountDownLatch(jobs);
        for (int i = 0; i < jobs; i++) {
            assertTrue(pool.submit(i % 2 == 0 ? END_BAND : SPHERE, i, i, 0, false, done::countDown, NOTHING));
        }
        await(done);
        assertEquals(0, pool.stats().queued());
        pool.close();
        assertTrue(pool.awaitQuiescent(WAIT_MS));
    }
}
