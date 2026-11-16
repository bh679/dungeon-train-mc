package games.brennan.dungeontrain.effect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * When the client should ask the server about an effect stuck at 00:00. The client counts an effect down
 * but never removes it: it waits for the server's remove packet. An effect the server never had (a mod that
 * applied it on the client too, for a hit only the client saw) gets no such packet and sits at 00:00 for
 * good. An effect is "stuck" once it has been at zero for {@link #GRACE_TICKS}; it is then due, and due
 * again every {@link #RETRY_TICKS} while it stays stuck. The server answers, so a stall that only makes the
 * client run ahead is never mistaken for a ghost.
 *
 * <p>Pure bookkeeping over any key type so it can be tested without a game; ticks are the player's own
 * {@code tickCount}, which stops while the game is paused.</p>
 *
 * @param <K> how an effect is identified
 */
public final class StuckEffectSchedule<K> {

    /** Two seconds at zero: well past the round trip a real removal takes. */
    public static final int GRACE_TICKS = 40;
    /** Five seconds between asks while an effect stays stuck. */
    public static final int RETRY_TICKS = 100;

    private final Map<K, Integer> zeroSince = new HashMap<>();
    private final Map<K, Integer> lastAsked = new HashMap<>();

    /**
     * Records which effects are at zero on {@code tick} and returns the ones to ask about now. Effects no
     * longer at zero (removed, or re-synced with time left) are forgotten.
     */
    public List<K> due(Collection<K> atZero, int tick) {
        zeroSince.keySet().retainAll(atZero);
        lastAsked.keySet().retainAll(atZero);
        List<K> due = new ArrayList<>();
        for (K key : atZero) {
            int since = zeroSince.computeIfAbsent(key, k -> tick);
            if (tick - since < GRACE_TICKS) continue;
            Integer asked = lastAsked.get(key);
            if (asked != null && tick - asked < RETRY_TICKS) continue;
            lastAsked.put(key, tick);
            due.add(key);
        }
        return due;
    }

    /** Forgets everything, e.g. when the local player changes. */
    public void clear() {
        zeroSince.clear();
        lastAsked.clear();
    }
}
