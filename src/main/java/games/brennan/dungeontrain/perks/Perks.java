package games.brennan.dungeontrain.perks;

import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TEMPORARY SHIM for the perks foundation (perks-tier1 session). Same signatures the real
 * {@code Perks} lands with; backed by a static per-player map a dev command sets. Delete this
 * class once the foundation PR is on main (effect classes keep compiling unchanged).
 */
public final class Perks {

    private static final Map<UUID, Map<String, Integer>> RANKS = new ConcurrentHashMap<>();

    private Perks() {}

    /** Active points on {@code perkId} for this life; 0 when not active or no player. */
    public static int rank(Player player, String perkId) {
        if (player == null) return 0;
        Map<String, Integer> ranks = RANKS.get(player.getUUID());
        return ranks == null ? 0 : ranks.getOrDefault(perkId, 0);
    }

    public static boolean isUnlocked(Player player, String perkId) {
        return rank(player, perkId) > 0;
    }

    /** Dev-only: shim setter behind {@code /dungeontrain debug perk set}. */
    public static void shimSet(Player player, String perkId, int rank) {
        RANKS.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>()).put(perkId, Math.max(0, rank));
    }
}
