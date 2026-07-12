package games.brennan.dungeontrain.platform.event;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal, reflection-free callback holder — Dungeon Train's loader-neutral
 * stand-in for a single NeoForge event type. One {@link DtEvent} instance per
 * EVENT TYPE (never per subscriber) lives as a static field on {@link DtEvents}.
 *
 * <p>Game logic registers a listener with {@link #register(Object)}; a
 * loader-specific <em>bridge</em> (e.g. {@code NeoForge*Bridge} in the root
 * module) subscribes the real NeoForge event once and, when it fires, iterates
 * {@link #listeners()} invoking each in registration order. {@link DtEvent}
 * deliberately knows nothing about the callback's signature — the bridge holds
 * the exact call shape — so a single tiny class serves every event type.</p>
 *
 * <h2>Cancellation</h2>
 * <p>There is no separate cancellable subtype. When an event is cancellable the
 * category's callback interface returns {@code boolean} (see e.g.
 * {@code DtCommandCallback}); the bridge iterates {@link #listeners()}, stops on
 * the FIRST listener that returns {@code true}, and maps that to the NeoForge
 * event's {@code setCanceled(true)} — the same short-circuit-on-first-cancel
 * contract as a NeoForge cancellable event. Non-cancellable categories use a
 * {@code void} callback and the bridge simply invokes every listener.</p>
 *
 * <h2>Threading</h2>
 * <p>Registration happens once, synchronously, during mod construction (before
 * any world/server/game thread exists), so it always happens-before any bridge
 * invocation. The backing list is a {@link CopyOnWriteArrayList} purely as
 * belt-and-braces visibility insurance across the construct-thread → server /
 * render-thread handoff; it is never mutated after startup on the hot path.</p>
 *
 * @param <T> the category's callback (functional) interface
 */
public final class DtEvent<T> {

    private final List<T> listeners = new CopyOnWriteArrayList<>();

    /** Add a listener. Invoked once per handler at startup; order is preserved. */
    public void register(T listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Listeners in registration order — iterated by the loader bridge on fire. */
    public Iterable<T> listeners() {
        return listeners;
    }

    /** True when no handler is registered — lets a bridge skip work cheaply. */
    public boolean isEmpty() {
        return listeners.isEmpty();
    }
}
