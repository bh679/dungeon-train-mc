package games.brennan.dungeontrain.platform.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal, reflection-free callback holder — Dungeon Train's loader-neutral
 * stand-in for a single NeoForge event type. One {@link DtEvent} instance per
 * EVENT TYPE (never per subscriber) lives as a static field on {@link DtEvents}.
 *
 * <p>Game logic registers a listener with {@link #register(Object)} (or
 * {@link #register(DtPriority, Object)} to keep a handler's original
 * {@code EventPriority}); a loader-specific <em>bridge</em> (e.g.
 * {@code NeoForge*Bridge} in the root module) subscribes the real NeoForge event
 * once per used priority tier and, when it fires, iterates the matching bucket
 * invoking each listener in registration order. {@link DtEvent} deliberately
 * knows nothing about the callback's signature — the bridge holds the exact call
 * shape — so a single tiny class serves every event type.</p>
 *
 * <h2>Priority tiers</h2>
 * <p>Listeners are bucketed by {@link DtPriority}. {@link #register(Object)}
 * targets {@link DtPriority#NORMAL} (the common case). A bridge that needs to
 * preserve cross-mod ordering subscribes once per tier DT uses and fires only
 * {@link #listeners(DtPriority)} for that tier (see {@link DtPriority}). A bridge
 * for an event where DT only ever used a single priority can instead call
 * {@link #listeners()}, which returns every bucket concatenated in NeoForge
 * firing order (HIGHEST → LOWEST, registration order within a tier) — identical
 * behaviour when only one tier is populated.</p>
 *
 * <h2>Cancellation</h2>
 * <p>There is no separate cancellable subtype. When an event is cancellable the
 * category's callback interface returns {@code boolean} (see e.g.
 * {@code DtCommandCallback}); the bridge iterates listeners, stops on the FIRST
 * listener that returns {@code true}, and maps that to the NeoForge event's
 * {@code setCanceled(true)} — the same short-circuit-on-first-cancel contract as
 * a NeoForge cancellable event. Non-cancellable categories use a {@code void}
 * callback and the bridge simply invokes every listener.</p>
 *
 * <h2>Threading</h2>
 * <p>Registration happens once, synchronously, during mod construction (before
 * any world/server/game thread exists), so it always happens-before any bridge
 * invocation. The backing lists are {@link CopyOnWriteArrayList} purely as
 * belt-and-braces visibility insurance across the construct-thread → server /
 * render-thread handoff; they are never mutated after startup on the hot path.</p>
 *
 * @param <T> the category's callback (functional) interface
 */
public final class DtEvent<T> {

    /** One bucket per {@link DtPriority}, indexed by ordinal (HIGHEST..LOWEST). */
    private final List<T>[] buckets;

    @SuppressWarnings("unchecked")
    public DtEvent() {
        DtPriority[] tiers = DtPriority.values();
        this.buckets = new List[tiers.length];
        for (int i = 0; i < tiers.length; i++) {
            this.buckets[i] = new CopyOnWriteArrayList<>();
        }
    }

    /**
     * Add a listener at {@link DtPriority#NORMAL}. Invoked once per handler at
     * startup; order within the tier is preserved.
     */
    public void register(T listener) {
        register(DtPriority.NORMAL, listener);
    }

    /**
     * Add a listener at an explicit priority tier (mirrors the handler's former
     * {@code @SubscribeEvent(priority = …)}). Order within a tier is preserved.
     */
    public void register(DtPriority priority, T listener) {
        buckets[priority.ordinal()].add(Objects.requireNonNull(listener, "listener"));
    }

    /** Listeners in one priority tier, in registration order — for per-tier bridges. */
    public Iterable<T> listeners(DtPriority priority) {
        return buckets[priority.ordinal()];
    }

    /**
     * Every listener across all tiers, concatenated in NeoForge firing order
     * (HIGHEST → LOWEST, registration order within a tier). Use only for events
     * where DT used a single priority (all others must fire per tier to keep
     * cross-mod ordering).
     */
    public Iterable<T> listeners() {
        List<T> all = new ArrayList<>();
        for (List<T> bucket : buckets) {
            all.addAll(bucket);
        }
        return all;
    }

    /** True when the given tier has no listener — lets a bridge skip work cheaply. */
    public boolean isEmpty(DtPriority priority) {
        return buckets[priority.ordinal()].isEmpty();
    }

    /** True when no handler is registered in any tier. */
    public boolean isEmpty() {
        for (List<T> bucket : buckets) {
            if (!bucket.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
