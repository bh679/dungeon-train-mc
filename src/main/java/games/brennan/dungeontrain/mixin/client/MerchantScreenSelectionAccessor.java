package games.brennan.dungeontrain.mixin.client;

import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Writes {@link MerchantScreen}'s private selected trade and scroll. Reroll Trades replaces the whole
 * offer list, which can be shorter than before; vanilla's render reads {@code offers.get(shopItem)}
 * without a bounds check, so {@link games.brennan.dungeontrain.client.RestockVillagerButton} puts the
 * selection back on the first trade when the rerolled list arrives.
 */
@Mixin(MerchantScreen.class)
public interface MerchantScreenSelectionAccessor {

    @Accessor("shopItem")
    void dungeontrain$setShopItem(int index);

    @Accessor("scrollOff")
    void dungeontrain$setScrollOff(int offset);
}
