package games.brennan.dungeontrain.difficulty;

import games.brennan.adventureitemnames.api.NameComposer;
import games.brennan.adventureitemstats.api.StatsModifier;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.Optional;

/**
 * Scales villager-SOLD items with the villager's own carriage position —
 * the beyond-Master trade progression. Composes the exact same decorator
 * calls as mob gear ({@link DifficultyApplier}) and chest loot
 * ({@code ContainerContentsRoller}): difficulty-scaled enchant → AIN name →
 * AIS stats, plus enchant-value-proportional emerald pricing (2^(level−1)
 * emeralds per enchant — the same curve Trade Everything uses to value
 * enchants, so buy and sell prices agree). Costs above 64 emeralds convert
 * to emerald blocks, which players can pay via TE's purchase-time block
 * breaking.
 *
 * <p>Only trade RESULTS with emerald costs are repriced; costs paid in goods
 * (wheat → emerald offers etc.) are never touched.</p>
 */
public final class TradeGearScaler {

    /** Enchant power ceiling — comfortably past the vanilla 30-level table. */
    private static final int MAX_POWER = 60;

    private TradeGearScaler() {}

    /**
     * Returns a scaled copy of {@code offer}, or the offer unchanged when
     * scaling is disabled / nothing applies. {@code posTier} is the villager's
     * own {@link DifficultyProgression#positionTier}; {@code carriageIndex}
     * its raw pIdx (the min-carriage gate keys off the raw index).
     */
    public static MerchantOffer scale(MerchantOffer offer, int carriageIndex, int posTier,
                                      RandomSource rng, HolderLookup.Provider registries) {
        if (!DungeonTrainConfig.getVillagerTradeScalingEnabled()) return offer;
        if (posTier <= 0) return offer;

        ItemStack result = offer.getResult().copy();

        boolean enchantScaled = false;
        if (Math.abs(carriageIndex) >= DungeonTrainConfig.getVillagerTradeScalingMinCarriage()) {
            int power = enchantPower(posTier, DungeonTrainConfig.getVillagerTradeScalingTiersPerStep());
            ItemStack rescaled = rescaleEnchants(result, power, rng, registries);
            if (rescaled != null) {
                result = rescaled;
                enchantScaled = true;
            }
        }

        // AIS stats + AIN name mirror mob gear / chest loot; both no-op on
        // items they don't apply to (food, maps, ...). Stats roll for a tier
        // slightly AHEAD of the villager's own — shop gear is aspirational:
        // +rand(0..max(1, tier/5)) tiers (1 → +0-1, 10 → +0-2, 20 → +0-4, 40 → +0-8).
        NameComposer.applyName(result, rng);
        int statTier = posTier + rng.nextInt(statLookaheadMax(posTier) + 1);
        StatsModifier.applyStats(result, rng,
            ItemStatLevelScaling.primaryStatBonus(result, statTier));

        ItemCost costA = offer.getItemCostA();
        if (enchantScaled) {
            costA = repriceForEnchants(costA, result);
        }

        return new MerchantOffer(costA, offer.getItemCostB(), result,
            0, offer.getMaxUses(), offer.getXp(), offer.getPriceMultiplier());
    }

    /**
     * Max tiers of stat look-ahead: ~20% of the current tier, floored at 1 —
     * 1 → 1, 10 → 2, 20 → 4, 40 → 8. Package-private for unit tests.
     */
    static int statLookaheadMax(int posTier) {
        return Math.max(1, posTier / 5);
    }

    /**
     * Enchant power: {@code min(60, 5 + 10·steps)}, steps = 1 + posTier/tiersPerStep
     * → 15, 25, 35… every {@code tiersPerStep} difficulty tiers. Package-private
     * for unit tests.
     */
    static int enchantPower(int posTier, int tiersPerStep) {
        int steps = 1 + posTier / Math.max(1, tiersPerStep);
        return Math.min(MAX_POWER, 5 + 10 * steps);
    }

    /**
     * Strip-and-re-roll enchants at {@code power} for enchantable results and
     * (enchanted) books. Returns the new stack, or null when the item takes no
     * enchants (leave the offer's enchant state alone).
     */
    private static ItemStack rescaleEnchants(ItemStack result, int power,
                                             RandomSource rng, HolderLookup.Provider registries) {
        if (registries == null) return null;
        Optional<HolderSet.Named<Enchantment>> pool = registries
            .lookup(Registries.ENCHANTMENT)
            .flatMap(reg -> reg.get(EnchantmentTags.IN_ENCHANTING_TABLE));
        if (pool.isEmpty()) return null;

        ItemStack base;
        if (result.is(Items.ENCHANTED_BOOK)) {
            // Re-roll the book from scratch; enchantItem turns BOOK back into
            // an ENCHANTED_BOOK with table-rolled stored enchants.
            base = new ItemStack(Items.BOOK, result.getCount());
        } else if (result.isEnchantable() || !result.getEnchantments().isEmpty()) {
            base = result.copy();
            base.set(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        } else {
            return null;
        }
        // enchantItem may return a different stack instance (book case) — capture it.
        return EnchantmentHelper.enchantItem(rng, base, power, pool.get().stream());
    }

    /**
     * Emerald cost with the enchant premium folded in: Σ 2^(level−1) emeralds
     * over the result's enchants (stored-book enchants included). Totals past
     * 64 convert to emerald blocks (⌈total/9⌉, capped at a stack). Non-emerald
     * costs are returned unchanged.
     */
    private static ItemCost repriceForEnchants(ItemCost costA, ItemStack result) {
        if (costA.item().value() != Items.EMERALD) return costA;
        int premium = enchantPremiumEmeralds(result);
        if (premium <= 0) return costA;
        int total = costA.count() + premium;
        if (total <= 64) {
            return new ItemCost(Items.EMERALD, total);
        }
        int blocks = Math.min(64, (total + 8) / 9);
        return new ItemCost(Items.EMERALD_BLOCK, blocks);
    }

    /** Package-private for unit tests. */
    static int enchantPremiumEmeralds(ItemStack result) {
        long premium = totalPremium(result.get(DataComponents.ENCHANTMENTS))
            + totalPremium(result.get(DataComponents.STORED_ENCHANTMENTS));
        return (int) Math.min(Integer.MAX_VALUE, premium);
    }

    private static long totalPremium(ItemEnchantments enchantments) {
        if (enchantments == null) return 0;
        long sum = 0;
        for (var entry : enchantments.entrySet()) {
            int level = Math.min(entry.getIntValue(), 12);
            if (level > 0) sum += 1L << (level - 1);
        }
        return sum;
    }
}
