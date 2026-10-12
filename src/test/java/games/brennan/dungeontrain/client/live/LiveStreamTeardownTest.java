package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Taking the Live Eye off froze the game for ~4 s: the stream's teardown (ffmpeg's last segment,
 * the final upload, the relay stop) ran on the render thread. {@link LiveTeardown} runs it on its
 * own thread, in order, and one failing step never skips the rest.
 */
class LiveStreamTeardownTest {

    @Test
    void runAsyncReturnsWithoutWaitingOnABlockingStep() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        long start = System.nanoTime();
        Thread t = LiveTeardown.runAsync(List.of(new LiveTeardown.Step("slow", release::await)), done::countDown);
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(tookMs < 500, "runAsync blocked the caller for " + tookMs + " ms");
        assertEquals(1, done.getCount(), "onDone ran before the steps finished");
        assertEquals(LiveTeardown.THREAD_NAME, t.getName());
        assertTrue(t.isDaemon());

        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "onDone never ran");
    }

    @Test
    void stepsRunInOrderOffTheCallingThread() throws Exception {
        List<String> ran = new CopyOnWriteArrayList<>();
        List<Thread> threads = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        LiveTeardown.runAsync(List.of(
            new LiveTeardown.Step("encoder", () -> { ran.add("encoder"); threads.add(Thread.currentThread()); }),
            new LiveTeardown.Step("upload", () -> ran.add("upload")),
            new LiveTeardown.Step("relay", () -> ran.add("relay")),
            new LiveTeardown.Step("delete", () -> ran.add("delete"))), done::countDown);

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("encoder", "upload", "relay", "delete"), ran);
        assertNotEquals(Thread.currentThread(), threads.get(0));
    }

    @Test
    void aFailingStepDoesNotSkipTheRest() {
        List<String> ran = new CopyOnWriteArrayList<>();
        LiveTeardown.runAll(List.of(
            new LiveTeardown.Step("encoder", () -> { throw new IllegalStateException("ffmpeg gone"); }),
            new LiveTeardown.Step("upload", () -> ran.add("upload")),
            new LiveTeardown.Step("delete", () -> ran.add("delete"))));

        assertEquals(List.of("upload", "delete"), ran);
    }

    @Test
    void onDoneRunsEvenWhenAStepThrows() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        LiveTeardown.runAsync(List.of(new LiveTeardown.Step("boom", () -> { throw new RuntimeException("x"); })),
            done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }
}
