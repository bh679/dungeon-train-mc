package games.brennan.dungeontrain.perks.effects.alchemist;

import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Brewmaster's hook for both kinds of stand: a bottle a brew just made, rolled with its brewer's rank
 * ({@link BrewmasterMath}). A placed stand's brewer is whoever last opened it; a pocket stand's is its owner.
 */
public final class Brewmaster {

    /** A placed brewing stand's bottle slots. */
    public static final int STAND_BOTTLES = 3;

    private Brewmaster() {}

    /** {@code brewed} as {@code brewer}'s Brewmaster rank makes it: unchanged without the perk. */
    public static ItemStack strengthen(Player brewer, ItemStack brewed) {
        if (brewer == null || brewed.isEmpty()) return brewed;
        int rank = Perks.rank(brewer, AlchemistPerks.BREWMASTER);
        if (rank <= 0) return brewed;
        return BrewmasterMath.upgrade(brewed, BrewmasterMath.roll(rank, brewer.getRandom()));
    }
}
