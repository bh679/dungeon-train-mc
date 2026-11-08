package games.brennan.dungeontrain.difficulty;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * A merchant selling Mending (on the item, or stored in an enchanted book) only ever has
 * {@link #MAX_STOCK} of it per restock. Applied to every {@code MerchantOffer} as it is built,
 * loaded or synced by {@code MerchantOfferMendingStockMixin}, so train villagers, world villagers,
 * wandering traders and offers already in a save all agree. Only the sold item counts: a trade
 * that takes Mending as payment is untouched.
 */
public final class MendingTradeStock {

    /** Mending trades can be bought this many times before the merchant restocks. */
    public static final int MAX_STOCK = 1;

    private MendingTradeStock() {}

    /** True when {@code result} carries Mending, on the item or stored in a book. */
    public static boolean sellsMending(ItemStack result) {
        if (result == null || result.isEmpty()) return false;
        return hasMending(result.get(DataComponents.ENCHANTMENTS))
            || hasMending(result.get(DataComponents.STORED_ENCHANTMENTS));
    }

    /** The offer's stock with the Mending cap applied; never raises it. Package-private for unit tests. */
    static int limitStock(int maxUses, boolean sellsMending) {
        return sellsMending ? Math.min(maxUses, MAX_STOCK) : maxUses;
    }

    /** {@link #limitStock(int, boolean)} for the offer that sells {@code result}. */
    public static int limitStock(int maxUses, ItemStack result) {
        return limitStock(maxUses, sellsMending(result));
    }

    private static boolean hasMending(ItemEnchantments enchantments) {
        if (enchantments == null || enchantments.isEmpty()) return false;
        for (var holder : enchantments.keySet()) {
            if (holder.is(Enchantments.MENDING)) return true;
        }
        return false;
    }
}
