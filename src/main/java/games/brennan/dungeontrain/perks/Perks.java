package games.brennan.dungeontrain.perks;

import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * TEMPORARY SHIM for the perks foundation (sibling PR, package {@code games.brennan.dungeontrain.perks}).
 * Same signatures as the real {@code Perks}; ranks live in a static map set by
 * {@link PerkShimCommand} (dev builds only). Delete this file once the foundation is on main.
 */
public final class Perks {

    private static final Map<UUID, Map<String, Integer>> RANKS = new HashMap<>();

    private Perks() {}

    /** Active points on {@code perkId} for {@code player}; 0 when the perk is not active. */
    public static int rank(Player player, String perkId) {
        if (player == null || perkId == null) return 0;
        Map<String, Integer> mine = RANKS.get(player.getUUID());
        return mine == null ? 0 : mine.getOrDefault(perkId, 0);
    }

    public static boolean isUnlocked(Player player, String perkId) {
        return rank(player, perkId) > 0;
    }

    static void set(Player player, String perkId, int rank) {
        RANKS.computeIfAbsent(player.getUUID(), k -> new HashMap<>()).put(perkId, Math.max(0, rank));
    }

    static void clear(Player player) {
        RANKS.remove(player.getUUID());
    }
}
