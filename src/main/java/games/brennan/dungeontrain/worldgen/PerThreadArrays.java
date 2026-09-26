package games.brennan.dungeontrain.worldgen;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * A per-thread copy of a shared {@code static} array, for third-party world generation that shuffles such an
 * array in place (see {@code mixin/betterend/BetterEnd*PerThreadMixin}). DT decorates BetterEnd samples on its
 * own sampler threads while the End also generates on its worldgen thread; two features shuffling one shared
 * array at once scramble each other's order. Each thread gets its own copy of each array instead.
 */
public final class PerThreadArrays {

    private static final ThreadLocal<Map<Object[], Object[]>> COPIES = ThreadLocal.withInitial(IdentityHashMap::new);

    private PerThreadArrays() {}

    /** This thread's copy of {@code shared}, made on first use. */
    @SuppressWarnings("unchecked")
    public static <T> T[] of(T[] shared) {
        return (T[]) COPIES.get().computeIfAbsent(shared, Object[]::clone);
    }
}
