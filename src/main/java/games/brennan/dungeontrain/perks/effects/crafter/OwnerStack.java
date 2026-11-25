package games.brennan.dungeontrain.perks.effects.crafter;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The owner stack behind {@link EnchantContext#pushOwner}, gated so that entities which cannot take
 * the Enchant Bonus (mobs, players at rank 0) cost one volatile read and nothing else.
 *
 * <p>A bracket pushes only when its entity qualifies, or when this thread already holds an entry (then
 * it pushes a mask, so a mob's read nested inside a player's read never sees that player). Exit never
 * re-asks whether the entity qualifies: a bracket that skipped entered at local depth 0, and by nesting
 * it leaves at depth 0, so its exit finds nothing to pop. A bracket that pushed always pops its own
 * entry. A rank or perks-on flip between enter and exit, on this thread or the other one in
 * singleplayer, therefore cannot unbalance the stack.</p>
 *
 * <p>{@link #live} counts entries held on every thread; while it is 0 no thread-local is touched.</p>
 */
final class OwnerStack {

    /** Masks an outer owner: ArrayDeque rejects nulls. */
    private static final Object NO_OWNER = new Object();

    private final ThreadLocal<ArrayDeque<Object>> local = ThreadLocal.withInitial(ArrayDeque::new);
    private final AtomicInteger live = new AtomicInteger();

    void enter(Object who, boolean qualifies) {
        if (!qualifies && live.get() == 0) return;
        ArrayDeque<Object> stack = local.get();
        if (qualifies) {
            stack.push(who);
        } else if (!stack.isEmpty()) {
            stack.push(NO_OWNER);
        } else {
            return;
        }
        live.incrementAndGet();
    }

    void exit() {
        if (live.get() == 0) return;
        ArrayDeque<Object> stack = local.get();
        if (stack.isEmpty()) return;
        stack.pop();
        live.decrementAndGet();
    }

    /** The owner of the innermost bracket on this thread, or null (none, or masked). */
    Object top() {
        if (live.get() == 0) return null;
        Object top = local.get().peek();
        return top == NO_OWNER ? null : top;
    }

    /** Entries held across all threads. Tests only. */
    int live() {
        return live.get();
    }

    /** Entries held on this thread. Tests only. */
    int localDepth() {
        return local.get().size();
    }
}
