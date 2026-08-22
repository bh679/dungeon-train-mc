package games.brennan.dungeontrain.compat;

import games.brennan.tradeeverything.api.TradeEverythingApi;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.OptionalInt;

/**
 * Bridge into Trade Everything's valuation API. Hard imports are confined to
 * this class; the caller gates on {@code ModList.isLoaded("tradeeverything")}
 * + {@code catch (Throwable)} (same pattern as {@link EnderChestLockBridge})
 * so a build predating the API degrades gracefully.
 *
 * <p>Values are in sixteenths of an emerald (16 = 1 emerald).</p>
 */
public final class TradeEverythingBridge {

    /**
     * 8 sixteenths × the default 0.75 payout margin = exactly 6 payout items
     * per book — "a narrative book is worth 6 coal/paper".
     */
    private static final int WRITTEN_BOOK_VALUE_SIXTEENTHS = 8;

    /** Raid-captain drop, not craftable — 5 emeralds. */
    private static final int OMINOUS_BANNER_VALUE_SIXTEENTHS = 80;

    /** +1 permanent backpack slot — 5 emeralds. */
    private static final int EDIBLE_BACKPACK_VALUE_SIXTEENTHS = 80;

    /** +9 slots, a 3×3 of edible backpacks — 45 emeralds, i.e. 9 × the plain one. */
    private static final int UPGRADED_BACKPACK_VALUE_SIXTEENTHS = 720;

    /**
     * The ominous banner is not its own item: vanilla stamps this translation
     * key into {@code ITEM_NAME} on a plain white banner
     * ({@code Raid.getLeaderBannerInstance}). Matching the component is the only
     * way to price it apart from ordinary banners — a config item-id override
     * would reprice every banner. {@code ITEM_NAME} (not {@code CUSTOM_NAME})
     * also means an anvil rename can't mint one.
     */
    private static final String OMINOUS_BANNER_NAME_KEY = "block.minecraft.ominous_banner";

    private TradeEverythingBridge() {}

    public static void install() {
        // All written books in DT are narrative artifacts (random/lectern/shared/
        // player-written) — flat value, overriding rarity/recipe derivation.
        TradeEverythingApi.registerValueProvider(stack ->
            stack.is(Items.WRITTEN_BOOK)
                ? OptionalInt.of(WRITTEN_BOOK_VALUE_SIXTEENTHS)
                : OptionalInt.empty());

        TradeEverythingApi.registerValueProvider(stack ->
            isOminousBanner(stack)
                ? OptionalInt.of(OMINOUS_BANNER_VALUE_SIXTEENTHS)
                : OptionalInt.empty());

        // Sibling-mod items, addressed by id so the sibling is never classloaded:
        // absent EdibleBackpacks simply means the override never matches.
        TradeEverythingApi.setItemOverride(
            ResourceLocation.fromNamespaceAndPath("ediblebackpacks", "edible_backpack"),
            EDIBLE_BACKPACK_VALUE_SIXTEENTHS);
        TradeEverythingApi.setItemOverride(
            ResourceLocation.fromNamespaceAndPath("ediblebackpacks", "upgraded_backpack"),
            UPGRADED_BACKPACK_VALUE_SIXTEENTHS);
    }

    /** See {@link #OMINOUS_BANNER_NAME_KEY} for why the check is component-based. */
    private static boolean isOminousBanner(ItemStack stack) {
        if (!stack.is(ItemTags.BANNERS)) return false;
        Component name = stack.get(DataComponents.ITEM_NAME);
        return name != null
            && name.getContents() instanceof TranslatableContents translatable
            && OMINOUS_BANNER_NAME_KEY.equals(translatable.getKey());
    }
}
