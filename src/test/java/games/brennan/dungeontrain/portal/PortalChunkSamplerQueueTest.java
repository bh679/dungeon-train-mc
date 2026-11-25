package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ground runs ahead of decoration on the sampler thread; each priority keeps its queue order. */
class PortalChunkSamplerQueueTest {

    @Test
    @DisplayName("Ground queued behind a room's decoration still runs before it")
    void groundJumpsDecoration() throws Exception {
        PortalChunkSamplerQueue queue = new PortalChunkSamplerQueue(task -> {
            Thread thread = new Thread(task, "sampler-test");
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> ran = new CopyOnWriteArrayList<>();
        // Hold the one thread so everything after this waits in the queue.
        queue.execute(PortalChunkSamplerQueue.Priority.GROUND, () -> {
            busy.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(busy.await(5, TimeUnit.SECONDS));
        for (int step = 0; step < 11; step++) {
            String name = "decorate-" + step;
            queue.execute(PortalChunkSamplerQueue.Priority.DECORATION, () -> ran.add(name));
        }
        queue.execute(PortalChunkSamplerQueue.Priority.GROUND, () -> ran.add("ground-a"));
        queue.execute(PortalChunkSamplerQueue.Priority.GROUND, () -> ran.add("ground-b"));
        release.countDown();
        queue.shutdownAndWait(5_000);

        assertEquals(13, ran.size());
        assertEquals(List.of("ground-a", "ground-b"), ran.subList(0, 2));
        for (int step = 0; step < 11; step++) assertEquals("decorate-" + step, ran.get(step + 2));
    }
}
