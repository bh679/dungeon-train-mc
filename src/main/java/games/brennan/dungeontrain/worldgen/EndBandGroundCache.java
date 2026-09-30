package games.brennan.dungeontrain.worldgen;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * The undecorated ground of recently sampled End chunks, shared between the End-band sampler threads so a
 * chunk being decorated can be ringed by its <b>real</b> neighbours ({@code endBandFeatureSpill}).
 *
 * <p>Each key is generated once: the first thread to ask generates it on its own thread, any other thread
 * asking meanwhile waits for that result. Entries are kept least-recently-used first and trimmed past
 * {@code cap} — only finished entries are dropped, never one still being generated. Generic in the value so
 * the sharing and trimming are unit-tested without a Minecraft chunk.</p>
 *
 * <p>Generation never asks the cache for another key, so two threads can never wait on each other.</p>
 */
public final class EndBandGroundCache<V> {

    /** Enough for a view-distance strip of band chunks plus the ring around it. */
    public static final int DEFAULT_CAP = 256;

    /** One End chunk of one pass look — a BoP pass runs a different generator over the same End coordinates. */
    public record Key(CycleLayout.Style style, long endChunk) {}

    private final int cap;
    private final LinkedHashMap<Key, CompletableFuture<V>> entries = new LinkedHashMap<>(64, 0.75f, true);

    public EndBandGroundCache(int cap) {
        this.cap = Math.max(1, cap);
    }

    /**
     * The value for {@code key}: generated on this thread if nobody has it yet, otherwise the cached one —
     * waiting if another thread is still generating it. Returns {@code null} if that other thread's
     * generation failed; a failure on this thread is rethrown after the key is released.
     */
    public V get(Key key, Supplier<V> generate) {
        CompletableFuture<V> future;
        boolean owner = false;
        synchronized (this) {
            future = entries.get(key);
            if (future == null) {
                future = new CompletableFuture<>();
                entries.put(key, future);
                owner = true;
                trimLocked();
            }
        }
        if (!owner) {
            try {
                return future.join();
            } catch (CompletionException | CancellationException e) {
                return null;
            }
        }
        V value;
        try {
            value = generate.get();
        } catch (RuntimeException | Error e) {
            future.completeExceptionally(e);
            synchronized (this) {
                entries.remove(key, future);
            }
            throw e;
        }
        if (value == null) {
            // Nothing to keep: release the key so a later request generates again.
            synchronized (this) {
                entries.remove(key, future);
            }
        }
        future.complete(value);
        return value;
    }

    /** True if {@code key} is cached or being generated. */
    public synchronized boolean contains(Key key) {
        return entries.containsKey(key);
    }

    public synchronized int size() {
        return entries.size();
    }

    /** Drop everything (server stop / world change). A generation still running completes into nothing. */
    public synchronized void clear() {
        entries.clear();
    }

    private void trimLocked() {
        if (entries.size() <= cap) return;
        Iterator<Map.Entry<Key, CompletableFuture<V>>> it = entries.entrySet().iterator();
        while (entries.size() > cap && it.hasNext()) {
            if (it.next().getValue().isDone()) it.remove();
        }
    }
}
