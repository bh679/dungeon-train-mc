package games.brennan.dungeontrain.perks;

import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TEMPORARY SHIM for the perks foundation being built in parallel (session "perks-tier1").
 *
 * <p>The real {@code games.brennan.dungeontrain.perks.Perks} keeps per-player {@code PerkState} synced to the
 * client and answers {@link #rank(Player, String)} from the player's active points. Until that lands on main
 * this shim answers from a static map that {@link PerkShimCommand} sets, so the per-class effect handlers
 * (e.g. {@code perks.effects.tank}) can be written against the final signature and tested in a dev client.
 * Delete this file (and {@link PerkShimCommand}) when rebasing onto the foundation PR.</p>
 */
public final class Perks {

    private static final Map<UUID, Map<String, Integer>> RANKS = new ConcurrentHashMap<>();

    private Perks() {}

    /** Active points ("rank") of {@code perkId} for {@code player}; 0 when the perk is not active. */
    public static int rank(Player player, String perkId) {
        if (player == null || perkId == null) return 0;
        Map<String, Integer> ranks = RANKS.get(player.getUUID());
        if (ranks == null) return 0;
        return Math.max(0, ranks.getOrDefault(perkId, 0));
    }

    /** True when the player has unlocked the perk (shim: any rank ever set above 0). */
    public static boolean isUnlocked(Player player, String perkId) {
        return rank(player, perkId) > 0;
    }

    /** Shim-only setter used by the dev command. Rank {@code <= 0} forgets the entry. */
    static void setRank(Player player, String perkId, int rank) {
        Map<String, Integer> ranks = RANKS.computeIfAbsent(player.getUUID(), id -> new ConcurrentHashMap<>());
        if (rank <= 0) ranks.remove(perkId); else ranks.put(perkId, rank);
    }
}
