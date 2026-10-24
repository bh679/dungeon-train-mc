package games.brennan.dungeontrain.perks;

import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SHIM: delete when the perks foundation (PerkRegistry / PerkState) merges to main.
 *
 * <p>Mirrors the foundation's {@code Perks.rank(Player, String)} signature so perk effects
 * written against it compile and are testable in a dev client before the real per-player,
 * client-synced state lands. Ranks live in memory only, set by
 * {@code /dungeontrain debug perk set <id> <rank>} ({@link PerkDevShim}).</p>
 */
public final class Perks {

    /** player UUID → (perk id → active points). Maps are replaced, never mutated in place. */
    private static final Map<UUID, Map<String, Integer>> RANKS = new ConcurrentHashMap<>();

    private Perks() {}

    /** The player's active points in {@code perkId}; 0 when the perk is not active. */
    public static int rank(Player player, String perkId) {
        if (player == null || perkId == null) return 0;
        Map<String, Integer> mine = RANKS.get(player.getUUID());
        if (mine == null) return 0;
        return mine.getOrDefault(perkId, 0);
    }

    /** Dev-only: set {@code perkId} to {@code rank} for {@code player} (0 clears it). */
    static void set(Player player, String perkId, int rank) {
        Map<String, Integer> prev = RANKS.getOrDefault(player.getUUID(), Map.of());
        Map<String, Integer> next = new ConcurrentHashMap<>(prev);
        if (rank <= 0) next.remove(perkId); else next.put(perkId, rank);
        RANKS.put(player.getUUID(), Map.copyOf(next));
    }
}
