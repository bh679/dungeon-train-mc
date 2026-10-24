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
     * (three extra data slots) for the enchanting screen to show struck through.
     */
    int[] dungeontrain$rollPower();
}
