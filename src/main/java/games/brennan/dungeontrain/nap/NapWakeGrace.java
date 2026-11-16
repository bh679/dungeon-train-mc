package games.brennan.dungeontrain.nap;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * No advancements for the wake itself. Waking puts the player in a new world, on a new train, in a
 * new biome, with their gear and counters reloaded, and vanilla and DT would award all of that as if
 * it were earned. {@code mixin/PlayerAdvancementsNapGraceMixin} refuses, meanwhile, only criteria of
 * the triggers in {@link #STATE_TRIGGERS}: those re-test standing state (where you are, what you hold,
 * a running counter) and fire again by themselves after the grace, so refusing them loses nothing.
 * Event criteria (a kill, an action, a book burned) pass, so nothing earned once is lost.
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

    /** Triggers that re-test standing state, by registry id: refused during the grace. */
    static final Set<String> STATE_TRIGGERS = Set.of(
        "minecraft:location", "minecraft:tick", "minecraft:inventory_changed", "minecraft:recipe_unlocked",
        "minecraft:effects_changed", "minecraft:enter_block", "minecraft:changed_dimension",
        "dungeontrain:biomes_visited", "dungeontrain:proximity_on_train", "dungeontrain:encountered_players",
        "dungeontrain:multiplayer_join", "dungeontrain:unique_chests_opened", "dungeontrain:carts_in_run",
        "dungeontrain:carts_both_directions", "dungeontrain:train_time", "dungeontrain:run_train_time",
        "dungeontrain:run_distance", "dungeontrain:lifetime_distance");

    private NapWakeGrace() {}

    /**
     * Whether a criterion of trigger {@code triggerId} is refused for {@code player} now. An unknown
     * trigger (null) is refused while the grace is open, as before.
     */
    public static boolean refuses(UUID player, String triggerId) {
        if (!isActive(player)) return false;
        return triggerId == null || STATE_TRIGGERS.contains(triggerId);
    }

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
