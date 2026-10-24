package games.brennan.dungeontrain.perks;

import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TEMPORARY SHIM behind {@link Perks}: an in-memory rank table settable from
 * {@code /dungeontrain debug perk set <id> <rank>} (dev builds only). Server side only, never
 * persisted, never synced. Goes away with the perks foundation PR.
 */
public final class PerkRankShim {

    private static final Map<UUID, Map<String, Integer>> RANKS = new ConcurrentHashMap<>();

    private PerkRankShim() {}

    static int rank(Player player, String perkId) {
        if (player == null || perkId == null) return 0;
        Map<String, Integer> mine = RANKS.get(player.getUUID());
        if (mine == null) return 0;
        Integer r = mine.get(perkId);
        return r == null ? 0 : Math.max(0, r);
    }

    /** Set (or clear, with 0) a rank for the dev command. */
    public static void set(UUID playerId, String perkId, int rank) {
        if (rank <= 0) {
            Map<String, Integer> mine = RANKS.get(playerId);
            if (mine != null) mine.remove(perkId);
            return;
        }
        RANKS.computeIfAbsent(playerId, id -> new ConcurrentHashMap<>()).put(perkId, rank);
    }

    /** Test seam. */
    public static void clear() {
        RANKS.clear();
    }
}
