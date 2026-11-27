package games.brennan.dungeontrain.perks.effects.crafter;

import net.minecraft.world.entity.player.Player;

/**
 * Duck interface the {@code EnchantmentMenu} mixin implements: who opened the table and, per row,
 * the power the vanilla roll must keep using after Enchant Cost lowers the displayed requirement.
 */
public interface CrafterEnchantMenu {

    Player dungeontrain$player();

    /**
     * Roll power per row (0 = use the displayed cost). Written by {@link CrafterEnchantCost} on the
     * server; it is also each row's cost before the discount, so the menu syncs it to the client
     * (three extra data slots) for the enchanting screen to show struck through. {@link #NOTHING_NEW}
     * marks a Re-enchant row with nothing left to add.
     */
    int[] dungeontrain$rollPower();

    /**
     * Re-enchant: whether the enchant just clicked started on an already enchanted item, then clears it.
     * Set on the server when the click re-rolls the row; read once by {@link ReenchantStamp}.
     */
    boolean dungeontrain$takeReenchantClick();

    /** {@link #dungeontrain$rollPower()} value marking a row whose rolls were all filtered out ("Nothing new"). */
    int NOTHING_NEW = -1;
}
