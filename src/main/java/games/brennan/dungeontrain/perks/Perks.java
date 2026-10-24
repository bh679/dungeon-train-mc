package games.brennan.dungeontrain.perks;

import net.minecraft.world.entity.player.Player;

/**
 * SHIM: stand-in for the perks foundation's {@code Perks} facade, with the exact signature the
 * effect handlers compile against. Backed by {@link PerkDevShim}'s dev-command map. Deleted when
 * the foundation PR (PerkRegistry / PerkState) lands on main; nothing else about the effects changes.
 */
public final class Perks {

    private Perks() {}

    /** Active points on a perk; 0 when not active. */
    public static int rank(Player player, String perkId) {
        return player == null ? 0 : PerkDevShim.rank(player.getUUID(), perkId);
    }

    /** Whether the perk is unlocked at all (the shim treats any set rank as unlocked). */
    public static boolean isUnlocked(Player player, String perkId) {
        return rank(player, perkId) > 0;
    }
}
