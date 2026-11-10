package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.trader.ExtraTradeCosts;
import games.brennan.tradeeverything.api.TradeEverythingApi;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Keeps villager-sold gear and enchanted books from costing less than Trade Everything would pay
 * for them. {@link GearValueProvider} prices gear by its stats, so a librarian's 20-emerald Mending
 * book is worth far more than that to the next villager — without this floor, buying from one and
 * selling to another prints emeralds.
 *
 * <p>The floor is the item's value × Trade Everything's payout margin, rounded up to whole
 * emeralds. Past a stack it switches to emerald blocks: cost A first, then B when the offer leaves
 * it free, then as many extra costs ({@link ExtraTradeCosts}) of a stack each as it needs. Offers
 * paid in goods are left alone.</p>
 */
public final class GearResaleFloor {

    private static final int EMERALDS_PER_BLOCK = 9;
    private static final int STACK = 64;

    private GearResaleFloor() {}

    /** {@code offer} with its emerald cost raised to the resale floor, or {@code offer} itself when already at or above it. */
    public static MerchantOffer apply(MerchantOffer offer) {
        ItemCost costA = offer.getItemCostA();
        int paid = emeraldsOf(costA) + offer.getItemCostB().map(GearResaleFloor::emeraldsOf).orElse(0)
            + ExtraTradeCosts.of(offer).stream().mapToInt(GearResaleFloor::emeraldsOf).sum();
        if (emeraldsOf(costA) <= 0) return offer;
        Optional<Double> value = GearValueProvider.emeralds(offer.getResult());
        if (value.isEmpty()) return offer;
        int floor = floorEmeralds(value.get(), TradeEverythingApi.config().resultMultiplier());
        if (paid >= floor) return offer;

        Optional<ItemCost> costB = offer.getItemCostB();
        if (floor <= STACK) {
            return rebuilt(offer, new ItemCost(Items.EMERALD, floor), costB, List.of());
        }
        int[] stacks = splitBlocks(ceilDiv(floor, EMERALDS_PER_BLOCK), costB.isEmpty());
        ItemCost newA = new ItemCost(Items.EMERALD_BLOCK, stacks[0]);
        int next = 1;
        if (costB.isEmpty() && stacks.length > 1) {
            costB = Optional.of(new ItemCost(Items.EMERALD_BLOCK, stacks[1]));
            next = 2;
        }
        List<ItemCost> extras = new ArrayList<>();
        for (int i = next; i < stacks.length; i++) extras.add(new ItemCost(Items.EMERALD_BLOCK, stacks[i]));
        return rebuilt(offer, newA, costB, extras);
    }

    private static MerchantOffer rebuilt(MerchantOffer offer, ItemCost a, Optional<ItemCost> b, List<ItemCost> extras) {
        ItemStack result = ExtraTradeCosts.withCosts(offer.getResult(), extras);
        return new MerchantOffer(a, b, result,
            offer.getUses(), offer.getMaxUses(), offer.getXp(), offer.getPriceMultiplier());
    }

    /** The lowest whole-emerald price at which reselling can't profit. Package-private for unit tests. */
    static int floorEmeralds(double valueEmeralds, double payoutMargin) {
        return (int) Math.ceil(valueEmeralds * payoutMargin - 1e-9);
    }

    /**
     * {@code blocks} emerald blocks as stack sizes in cost order: A, then B when {@code bFree}, then
     * extras, each a full stack but the last. Capped at {@link ExtraTradeCosts#MAX_COSTS} costs
     * (the B place counts even when B holds something else). Package-private for unit tests.
     */
    static int[] splitBlocks(int blocks, boolean bFree) {
        int maxStacks = bFree ? ExtraTradeCosts.MAX_COSTS : ExtraTradeCosts.MAX_COSTS - 1;
        int stacks = Math.min(maxStacks, Math.max(1, ceilDiv(blocks, STACK)));
        int[] out = new int[stacks];
        int left = Math.min(blocks, stacks * STACK);
        for (int i = 0; i < stacks; i++) {
            out[i] = Math.min(STACK, left);
            left -= out[i];
        }
        return out;
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
