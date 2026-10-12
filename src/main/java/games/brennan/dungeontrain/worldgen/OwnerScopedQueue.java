package games.brennan.dungeontrain.worldgen;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * A hand-over queue whose items remember who offered them: {@link #drain} delivers only the items its
 * owner offered and drops the rest. Used to pass worldgen-thread work to the server thread without it
 * ever reaching a <em>different</em> server — a worldgen worker can still be finishing a chunk for the
 * old server after its stop-time clear, and the next world opened in the same JVM must not receive it.
 *
 * <p>Owners compare by identity. Generic in both so it is unit-tested without Minecraft. Any thread
 * offers; one thread drains.</p>
 */
public final class OwnerScopedQueue<T> {

    private record Entry<T>(Object owner, T item) {}

    private final ConcurrentLinkedQueue<Entry<T>> queue = new ConcurrentLinkedQueue<>();

    /** Queue {@code item} for {@code owner}. Any thread. */
    public void offer(Object owner, T item) {
        queue.add(new Entry<>(owner, item));
    }

    /**
     * Hand every queued item offered by {@code owner} to {@code out}, oldest first, and discard the ones
     * offered by anyone else. Returns how many were discarded.
     */
    public int drain(Object owner, Consumer<? super T> out) {
        int dropped = 0;
        Entry<T> e;
        while ((e = queue.poll()) != null) {
            if (e.owner() == owner) out.accept(e.item());
            else dropped++;
        }
        return dropped;
    }

    /** Items waiting, whoever offered them. */
    public int size() {
        return queue.size();
    }

    public void clear() {
        queue.clear();
    }
}
