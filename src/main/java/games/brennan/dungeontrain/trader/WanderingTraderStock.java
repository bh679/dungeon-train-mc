package games.brennan.dungeontrain.trader;

import games.brennan.dungeontrain.compat.DisposableCamera;
import games.brennan.dungeontrain.item.LiveHeadpiecePendingTag;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The one Dungeon Train item every wandering trader carries, one in stock
 * ({@link games.brennan.dungeontrain.mixin.WanderingTraderStockMixin}).
 *
 * <p>The Live Eye is sold pending, like a loot-rolled one: on reaching the buyer's inventory
 * {@code event.LiveHeadpieceLootGate} resolves it through {@code event.LiveEyeLootResolver}, so a buyer
 * who can't have one gets a TV. The album is the {@link ModItems#YOUR_PHOTOALBUM} placeholder, which
 * becomes the buyer's own album when taken in hand.</p>
 */
public final class WanderingTraderStock {

    /** A price that fits one stack is paid in emeralds; above it, in emerald blocks. */
    private static final int MAX_EMERALD_COST = 64;
    private static final int EMERALDS_PER_BLOCK = 9;

    /** One purchase, then sold out. Wandering traders never restock. */
    private static final int STOCK = 1;

    private static final ResourceLocation EDIBLE_BACKPACK =
        ResourceLocation.fromNamespaceAndPath("ediblebackpacks", "edible_backpack");
    private static final ResourceLocation GOLDEN_EDIBLE_BACKPACK =
        ResourceLocation.fromNamespaceAndPath("ediblebackpacks", "golden_edible_backpack");

    /** What a trader may carry. {@code weight} is its share of the roll, {@code priceEmeralds} its full price. */
    enum Ware {
        CAMERA(5, 6),
        EDIBLE_BACKPACK(3, 10),
        GOLDEN_EDIBLE_BACKPACK(1, 90),
        PHOTO_ALBUM(3, 6),
        LIVE_EYE(1, 40);

        final int weight;
        final int priceEmeralds;

        Ware(int weight, int priceEmeralds) {
            this.weight = weight;
            this.priceEmeralds = priceEmeralds;
        }
    }

    private WanderingTraderStock() {}

    /** The offer for a newly stocked trader, or empty when none of its wares are registered. */
    public static Optional<MerchantOffer> createOffer(RandomSource rng) {
        List<Ware> stocked = Arrays.stream(Ware.values())
            .filter(ware -> !stackOf(ware).isEmpty())
            .toList();
        if (stocked.isEmpty()) return Optional.empty();
        Ware ware = pick(rng.nextInt(totalWeight(stocked)), stocked);
        return Optional.of(new MerchantOffer(costOf(ware.priceEmeralds), stackOf(ware), STOCK, 0, 0.0f));
    }

    static int totalWeight(List<Ware> wares) {
        return wares.stream().mapToInt(ware -> ware.weight).sum();
    }

    /** The ware a roll lands on. {@code roll} is uniform in [0, {@link #totalWeight}). Pure for the tests. */
    static Ware pick(int roll, List<Ware> wares) {
        int remaining = roll;
        for (Ware ware : wares) {
            if (remaining < ware.weight) return ware;
            remaining -= ware.weight;
        }
        throw new IllegalArgumentException("roll " + roll + " is past the total weight of " + wares);
    }

    /** Emerald blocks a price takes when it won't fit one stack of emeralds. Pure for the tests. */
    static int blocksFor(int priceEmeralds) {
        return Math.min(MAX_EMERALD_COST, (priceEmeralds + EMERALDS_PER_BLOCK - 1) / EMERALDS_PER_BLOCK);
    }

    private static ItemCost costOf(int priceEmeralds) {
        return priceEmeralds <= MAX_EMERALD_COST
            ? new ItemCost(Items.EMERALD, priceEmeralds)
            : new ItemCost(Items.EMERALD_BLOCK, blocksFor(priceEmeralds));
    }

    private static ItemStack stackOf(Ware ware) {
        return switch (ware) {
            case CAMERA -> DisposableCamera.create();
            case EDIBLE_BACKPACK -> stackOfId(EDIBLE_BACKPACK);
            case GOLDEN_EDIBLE_BACKPACK -> stackOfId(GOLDEN_EDIBLE_BACKPACK);
            case PHOTO_ALBUM -> new ItemStack(ModItems.YOUR_PHOTOALBUM.get());
            case LIVE_EYE -> pendingLiveEye();
        };
    }

    /** Another mod's item by id; empty when that mod's item isn't registered. */
    private static ItemStack stackOfId(ResourceLocation id) {
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static ItemStack pendingLiveEye() {
        ItemStack eye = new ItemStack(ModItems.LIVE_HEADPIECE.get());
        LiveHeadpiecePendingTag.markPending(eye);
        return eye;
    }
}
