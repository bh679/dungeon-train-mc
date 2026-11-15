package games.brennan.dungeontrain.nap;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * No advancements while a nap wakes. Waking puts the player in a new world, on a new train, in a
 * new biome, and vanilla and DT would award all of that as if it were earned
 * ({@code mixin/PlayerAdvancementsNapGraceMixin} refuses every criterion meanwhile).
 *
 * <p>Opened when the nap is loaded ({@link NapWake#tagFor}), held while the join placement is still
 * pending, and closed {@value #AFTER_PLACEMENT_TICKS} ticks after it finishes. Server-thread only.</p>
 */
public final class NapWakeGrace {

    /** 10 seconds after the player is on the deck. */
    static final int AFTER_PLACEMENT_TICKS = 200;

    /** Player to ticks left once placed; {@link #WAITING} while the placement is still pending. */
    private static final Map<UUID, Integer> GRACE = new HashMap<>();
    private static final int WAITING = -1;

    private NapWakeGrace() {}

    static void open(UUID player) {
        GRACE.put(player, WAITING);
    }

    public static boolean isActive(UUID player) {
        return !GRACE.isEmpty() && GRACE.containsKey(player);
    }

    /**
     * One server tick: a player whose placement is done starts (or keeps) counting down, and leaves
     * the grace when it runs out. {@code placementPending} says whether a player is still waiting to be
     * put on the train.
     */
    static void tick(Predicate<UUID> placementPending) {
        if (GRACE.isEmpty()) return;
        Iterator<Map.Entry<UUID, Integer>> it = GRACE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            if (placementPending.test(e.getKey())) {
                e.setValue(WAITING);
                continue;
            }
            int left = e.getValue() == WAITING ? AFTER_PLACEMENT_TICKS : e.getValue();
            if (left <= 1) {
                it.remove();
            } else {
                e.setValue(left - 1);
            }
        }
    }

    static void close(UUID player) {
        GRACE.remove(player);
    }

    static void clear() {
        GRACE.clear();
    }
}
