package games.brennan.dungeontrain.compat;

import games.brennan.tradeeverything.api.TradeEverythingApi;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.Optional;

/**
 * Keeps villager-sold gear and enchanted books from costing less than Trade Everything would pay
 * for them. {@link GearValueProvider} prices gear by its stats, so a librarian's 20-emerald Mending
 * book is worth far more than that to the next villager — without this floor, buying from one and
 * selling to another prints emeralds.
 *
 * <p>The floor is the item's value × Trade Everything's payout margin, rounded up to whole
 * emeralds. Past a stack it switches to emerald blocks, and past a stack of blocks it fills the
 * offer's second cost slot with blocks when that slot is free. Offers paid in goods are left alone.</p>
 */
public final class GearResaleFloor {

    private static final int EMERALDS_PER_BLOCK = 9;
    private static final int STACK = 64;

    private GearResaleFloor() {}

    /** {@code offer} with its emerald cost raised to the resale floor, or {@code offer} itself when already at or above it. */
    public static MerchantOffer apply(MerchantOffer offer) {
        ItemCost costA = offer.getItemCostA();
        int paid = emeraldsOf(costA);
        if (paid <= 0) return offer;
        Optional<Double> value = GearValueProvider.emeralds(offer.getResult());
        if (value.isEmpty()) return offer;
        int floor = floorEmeralds(value.get(), TradeEverythingApi.config().resultMultiplier());
        if (paid >= floor) return offer;

        Optional<ItemCost> costB = offer.getItemCostB();
        ItemCost newA;
        if (floor <= STACK) {
            newA = new ItemCost(Items.EMERALD, floor);
        } else {
            int blocks = ceilDiv(floor, EMERALDS_PER_BLOCK);
            newA = new ItemCost(Items.EMERALD_BLOCK, Math.min(STACK, blocks));
            if (blocks > STACK && costB.isEmpty()) {
                costB = Optional.of(new ItemCost(Items.EMERALD_BLOCK, Math.min(STACK, blocks - STACK)));
            }
        }
        return new MerchantOffer(newA, costB, offer.getResult(),
            offer.getUses(), offer.getMaxUses(), offer.getXp(), offer.getPriceMultiplier());
    }

    /** The lowest whole-emerald price at which reselling can't profit. Package-private for unit tests. */
    static int floorEmeralds(double valueEmeralds, double payoutMargin) {
        return (int) Math.ceil(valueEmeralds * payoutMargin - 1e-9);
    }

    private static int emeraldsOf(ItemCost cost) {
        Item item = cost.item().value();
        if (item == Items.EMERALD) return cost.count();
        if (item == Items.EMERALD_BLOCK) return cost.count() * EMERALDS_PER_BLOCK;
        return 0;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
