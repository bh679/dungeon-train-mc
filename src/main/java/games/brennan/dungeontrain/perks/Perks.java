package games.brennan.dungeontrain.perks;

import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TEMPORARY SHIM. The real perks foundation (registry, per-player state, client sync) is built by
 * the perks-tier1 session and lands on main as its own PR. Until then this class carries the exact
 * public signature effect code is written against, backed by a static per-player map a dev command
 * fills ({@code /dungeontrain debug perk set <id> <rank>}). Delete this file when the foundation
 * merges; nothing outside {@code perks/} may depend on anything but {@link #rank} and
 * {@link #isUnlocked}.
 */
public final class Perks {

    private static final Map<UUID, Map<String, Integer>> RANKS = new ConcurrentHashMap<>();

    private Perks() {}

    /** Active points in {@code perkId} for {@code player}; 0 when locked, inactive or unknown. */
    public static int rank(Player player, String perkId) {
        if (player == null || perkId == null) return 0;
        Map<String, Integer> ranks = RANKS.get(player.getUUID());
        if (ranks == null) return 0;
        return Math.max(0, ranks.getOrDefault(perkId, 0));
    }

    public static boolean isUnlocked(Player player, String perkId) {
        return rank(player, perkId) > 0;
    }

    /** Dev-only setter behind the shim; returns the rank now stored. */
    public static int devSet(UUID player, String perkId, int rank) {
        int clamped = Math.max(0, rank);
        RANKS.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).put(perkId, clamped);
        return clamped;
    }
}
