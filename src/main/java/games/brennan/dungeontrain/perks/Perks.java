package games.brennan.dungeontrain.perks;

import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TEMPORARY SHIM: the perks foundation (PerkState, sync, registry) lands on main in its own PR. Until
 * then this static map answers {@link #rank} with the same signature so the Archivist effects can be
 * built and tested; {@code /dungeontrain debug perk set <id> <rank>} fills it. Delete this file on the
 * rebase that brings the real {@code games.brennan.dungeontrain.perks.Perks} in.
 */
public final class Perks {

    private static final Map<UUID, Map<String, Integer>> RANKS = new ConcurrentHashMap<>();

    private Perks() {}

    /** The active points of {@code perkId} for {@code player}, 0 when not active. */
    public static int rank(Player player, String perkId) {
        if (player == null || perkId == null) return 0;
        Map<String, Integer> mine = RANKS.get(player.getUUID());
        return mine == null ? 0 : Math.max(0, mine.getOrDefault(perkId, 0));
    }

    public static boolean isUnlocked(Player player, String perkId) {
        return rank(player, perkId) > 0;
    }

    /** Dev command only. */
    public static void setRank(UUID player, String perkId, int rank) {
        RANKS.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).put(perkId, Math.max(0, rank));
    }
}
