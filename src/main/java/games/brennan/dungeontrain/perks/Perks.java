package games.brennan.dungeontrain.perks;

import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TEMPORARY SHIM for the perks foundation (sibling PR, not on main yet). Same signatures as the real
 * {@code Perks}: effect code reads {@link #rank} and {@link #isUnlocked} and never sees the difference.
 * Backed by a static map keyed by player UUID, filled only by the dev command
 * {@code /dungeontrain debug perk set <id> <rank>} ({@code command/PerkShimDebug}); in a dev singleplayer the
 * client and the integrated server share the JVM, so both sides read the same ranks.
 *
 * <p>Delete this file (and {@code PerkShimDebug}) when merging the foundation.</p>
 */
public final class Perks {

    private static final Map<UUID, Map<String, Integer>> RANKS = new ConcurrentHashMap<>();

    private Perks() {}

    /** Active points in {@code perkId} for {@code player}; 0 when not active. */
    public static int rank(Player player, String perkId) {
        Map<String, Integer> own = RANKS.get(player.getUUID());
        if (own == null) return 0;
        return own.getOrDefault(perkId, 0);
    }

    public static boolean isUnlocked(Player player, String perkId) {
        return rank(player, perkId) > 0;
    }

    /** Shim only: set a rank. A rank of 0 or less removes the entry. */
    public static void shimSet(Player player, String perkId, int rank) {
        Map<String, Integer> own = RANKS.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>());
        if (rank <= 0) own.remove(perkId); else own.put(perkId, rank);
    }

    /** Shim only: clear every rank for a player. */
    public static void shimClear(Player player) {
        RANKS.remove(player.getUUID());
    }

    /** Shim only: a read-only view of a player's ranks. */
    public static Map<String, Integer> shimView(Player player) {
        return Map.copyOf(RANKS.getOrDefault(player.getUUID(), Map.of()));
    }
}
