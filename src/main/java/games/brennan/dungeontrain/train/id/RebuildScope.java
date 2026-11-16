package games.brennan.dungeontrain.train.id;

import java.util.function.Supplier;

/**
 * The thread scope a carriage rebuild runs in, like
 * {@link games.brennan.dungeontrain.template.TestGate}: only {@link CarriageRebuilder} opens one, so
 * the train never sees it.
 *
 * <p>Inside it, the group-building code replays the verdicts the id recorded instead of asking the
 * live game — the portal lottery (which follows game modes), the transition-flatbed claims (which
 * follow riders) and the relay leases (which follow the pool) — and writes none of the records that
 * describe the real train at a carriage index: the rebuilt copy stands at no place on the line.</p>
 *
 * @param portal         the portal lottery fired for the group being rebuilt
 * @param transitionMask slots a transition flatbed claimed ({@code 1 << slot})
 */
public record RebuildScope(boolean portal, int transitionMask) {

    private static final ThreadLocal<RebuildScope> CURRENT = new ThreadLocal<>();

    /** The rebuild in scope on this thread, or {@code null} on the live train. */
    public static RebuildScope current() {
        return CURRENT.get();
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    /** Run {@code body} with {@code scope} in effect; nested scopes restore the outer one after. */
    public static <T> T with(RebuildScope scope, Supplier<T> body) {
        RebuildScope prev = CURRENT.get();
        CURRENT.set(scope);
        try {
            return body.get();
        } finally {
            if (prev == null) CURRENT.remove(); else CURRENT.set(prev);
        }
    }

    /** The recorded transition-flatbed slots as the assembler reads them, or {@code null} for none. */
    public boolean[] transitionSlots(int groupSize) {
        if (transitionMask == 0) return null;
        boolean[] slots = new boolean[groupSize];
        for (int i = 0; i < groupSize && i < 30; i++) slots[i] = (transitionMask & (1 << i)) != 0;
        return slots;
    }
}
