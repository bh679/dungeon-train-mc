package games.brennan.dungeontrain.perks;

import net.minecraft.world.entity.player.Player;

/**
 * TEMPORARY SHIM: the perks foundation (PerkRegistry, PerkState, sync, dev command) is landing on main
 * from its own PR. Until then this class exposes the same two reads effect handlers use, backed by
 * {@link PerkRankShim}. Delete this file (and {@link PerkRankShim}) when rebasing onto the foundation;
 * every call site keeps the same signature.
 */
public final class Perks {

    private Perks() {}

    /** The perk's active rank for {@code player}: 0 when not active (or not unlocked). */
    public static int rank(Player player, String perkId) {
        return PerkRankShim.rank(player, perkId);
    }

    /** Whether the perk is unlocked for {@code player}. The shim treats "has rank" as unlocked. */
    public static boolean isUnlocked(Player player, String perkId) {
        return rank(player, perkId) > 0;
    }
}
