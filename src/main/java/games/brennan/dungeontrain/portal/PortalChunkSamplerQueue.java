package games.brennan.dungeontrain.portal;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The chunk dimension sampler's one thread, with its work ordered so ground always goes first.
 *
 * <p>A pair cannot be planned, and so its corridor cannot cross, until its ground is in hand; its
 * decoration only grows things on ground that is already standing. With a plain FIFO a 3x3 room's
 * eleven decoration steps sat in front of every other pair's ground, so a second player's corridor
 * waited out somebody else's trees. Here a ground task queued after them still runs next, and tasks
 * of one priority keep the order they were queued in.</p>
 *
 * <p>Still a thread of its own and never {@code Util.backgroundExecutor()}: see
 * {@link PortalChunkTerrain}'s note on the deadlock that caused.</p>
 */
final class PortalChunkSamplerQueue {

    /** What a task is, which is what decides when it runs. Earlier constants run first. */
    enum Priority { GROUND, DECORATION }

    private final ThreadPoolExecutor executor;
    private final AtomicLong sequence = new AtomicLong();

    PortalChunkSamplerQueue(ThreadFactory threads) {
        // Only execute() is ever used: submit() would wrap the task in a FutureTask, which the
        // priority queue cannot compare.
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new PriorityBlockingQueue<>(), threads);
    }

    void execute(Priority priority, Runnable task) {
        executor.execute(new Prioritised(priority, sequence.getAndIncrement(), task));
    }

    /** For tests: stop taking work and wait for what is running. */
    void shutdownAndWait(long millis) throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(millis, TimeUnit.MILLISECONDS);
    }

    private record Prioritised(Priority priority, long sequence, Runnable task)
        implements Runnable, Comparable<Prioritised> {

        @Override
        public void run() {
            task.run();
        }

        @Override
        public int compareTo(Prioritised other) {
            int byPriority = priority.compareTo(other.priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }
}
