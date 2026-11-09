package games.brennan.dungeontrain.difficulty;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;

/**
 * A merchant selling Mending (on the item, or stored in an enchanted book) has a random
 * {@link #MIN_STOCK}-{@link #MAX_STOCK} of it per restock. Applied to every {@code MerchantOffer}
 * as it is built, loaded or synced by {@code MerchantOfferMendingStockMixin}, so train villagers,
 * world villagers, wandering traders and offers already in a save all agree. Only the sold item
 * counts: a trade that takes Mending as payment is untouched.
 *
 * <p>The constructor runs again on every save load, client sync and copy, so the stock is rolled
 * only while it is still above {@link #MAX_STOCK} (a fresh vanilla offer). Once rolled, the saved
 * and synced stock is in range and every later rebuild keeps it.</p>
 */
public final class MendingTradeStock {

    /** Fewest Mending trades a merchant can have before it restocks. */
    public static final int MIN_STOCK = 1;
    /** Most Mending trades a merchant can have before it restocks. */
    public static final int MAX_STOCK = 3;

    private MendingTradeStock() {}

    /** True when {@code result} carries Mending, on the item or stored in a book. */
    public static boolean sellsMending(ItemStack result) {
        if (result == null || result.isEmpty()) return false;
        return hasMending(result.get(DataComponents.ENCHANTMENTS))
            || hasMending(result.get(DataComponents.STORED_ENCHANTMENTS));
    }

    /**
     * The offer's stock with the Mending cap applied: a stock above {@link #MAX_STOCK} becomes
     * {@code roll}'s value, one already in range is kept. Package-private for unit tests.
     */
    static int limitStock(int maxUses, boolean sellsMending, IntSupplier roll) {
        if (!sellsMending || maxUses <= MAX_STOCK) return maxUses;
        return roll.getAsInt();
    }

    /** {@link #limitStock(int, boolean, IntSupplier)} for the offer that sells {@code result}. */
    public static int limitStock(int maxUses, ItemStack result) {
        return limitStock(maxUses, sellsMending(result), MendingTradeStock::rollStock);
    }

    /** A stock in {@code [MIN_STOCK, MAX_STOCK]}. Package-private for unit tests. */
    static int rollStock() {
        return ThreadLocalRandom.current().nextInt(MIN_STOCK, MAX_STOCK + 1);
    }

    private static boolean hasMending(ItemEnchantments enchantments) {
        if (enchantments == null || enchantments.isEmpty()) return false;
        for (var holder : enchantments.keySet()) {
            if (holder.is(Enchantments.MENDING)) return true;
        }
        return false;
    }
}
