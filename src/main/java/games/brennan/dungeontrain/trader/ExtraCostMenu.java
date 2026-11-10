package games.brennan.dungeontrain.trader;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * What {@code MerchantMenuExtraCostsMixin} adds to the villager trade menu: the container holding
 * extra-cost payments and the grid's scroll row. Cast a {@code MerchantMenu} to this.
 */
public interface ExtraCostMenu {

    /** One slot per extra cost, in cost order ({@link ExtraTradeCosts#MAX_EXTRA} slots). */
    SimpleContainer dungeontrain$backing();

    int dungeontrain$scrollRow();

    void dungeontrain$setScrollRow(int row);

    /** The trade the player has selected, or null before any is. */
    MerchantOffer dungeontrain$selectedOffer();
}
