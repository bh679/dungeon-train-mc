package games.brennan.dungeontrain.perks;

import games.brennan.dungeontrain.perks.effects.lover.PetHealthEffects;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SHIM: in-memory per-player perk ranks, set by {@code /dungeontrain debug perk set}. Replaced
 * by the foundation's synced {@code PerkState}. Writes copy the maps rather than mutate them.
 */
public final class PerkDevShim {

    private static volatile Map<UUID, Map<String, Integer>> ranks = Map.of();

    private PerkDevShim() {}

    public static int rank(UUID player, String perkId) {
        Map<String, Integer> own = ranks.get(player);
        if (own == null) return 0;
        return own.getOrDefault(perkId, 0);
    }

    /** Sets a rank and tells the effect handlers that need to re-apply on change. */
    public static synchronized void set(ServerPlayer player, String perkId, int rank) {
        Map<String, Integer> own = new HashMap<>(ranks.getOrDefault(player.getUUID(), Map.of()));
        if (rank <= 0) {
            own.remove(perkId);
        } else {
            own.put(perkId, rank);
        }
        Map<UUID, Map<String, Integer>> next = new HashMap<>(ranks);
        next.put(player.getUUID(), Map.copyOf(own));
        ranks = Map.copyOf(next);
        PetHealthEffects.onRankChanged(player);
    }
}
