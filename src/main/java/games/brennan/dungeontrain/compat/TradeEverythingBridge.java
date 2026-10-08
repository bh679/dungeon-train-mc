package games.brennan.dungeontrain.compat;

import games.brennan.tradeeverything.api.TradeEverythingApi;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.List;
import java.util.Map;
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

    /**
     * Every armor trim smithing template — a flat 2.2 emeralds. Values are integer
     * sixteenths, so 2.2 is not representable exactly (35.2); 35 is the nearest,
     * i.e. 2.1875 emeralds. Vanilla rarity is COMMON, so without this they resolve
     * to the 1-sixteenth floor and a Silence template sells for the same as a stick.
     */
    private static final int TRIM_TEMPLATE_VALUE_SIXTEENTHS = 35;

    /**
     * Bookshelf — four trade for 1 emerald. In 256ths of an emerald, not
     * sixteenths: the value is 5.5 sixteenths, and neither whole neighbour
     * quotes a 4 → 1 row (5 gives 9 → 2, 6 gives 11 → 3 — Trade Everything
     * skips a batch that rounds away more than 10%). {@code 4 × 88 × 0.75}
     * (the default payout margin) = 264, one emerald (256) with 3% to spare;
     * three (198) do not reach it. Library carriages are packed with them, so
     * one each was far too much. A librarian sells them for 9 emeralds: Trade
     * Everything 0.14.0+ pays the lower of this value and its 90% buy-back,
     * older builds paid 8 emeralds.
     */
    static final int BOOKSHELF_VALUE_256THS = 88;

    /**
     * Honey block — trades for 1 emerald each. 22 sixteenths × the default 0.75
     * payout margin = 16.5, so the payout clears exactly 1 emerald (16 would pay
     * out only 12 sixteenths).
     */
    private static final int HONEY_BLOCK_VALUE_SIXTEENTHS = 22;

    /**
     * Value that pays out exactly one emerald per multiple — the same 22 as the
     * honey block. {@code 22n × 0.75 = 16.5n} floors to n emeralds for every n < 32.
     */
    static final int EMERALD_PAYOUT_SIXTEENTHS = 22;

    /** 256ths of an emerald in one sixteenth — Trade Everything's finer override unit. */
    private static final int SIXTEENTH_IN_256THS = 16;

    /**
     * Things villagers sell, priced by hand, keyed by vanilla item path, in 256ths
     * of an emerald. Trade Everything pays the lower of an item's value and 90% of
     * the villager's sell price, and these default to next to nothing (a bell at
     * the 1-sixteenth floor), so a 36-emerald bell sold back for a twentieth of an
     * emerald. The prices are what one pays after the 0.75 margin: whole emeralds
     * use the 22-sixteenths-per-emerald convention, the fractions are picked so the
     * first batch within TE's 10% rounding tolerance is the stated row. Gear prices
     * are for an undamaged item — TE scales worn ones down.
     */
    static final Map<String, Integer> VILLAGER_STOCK_VALUES_256THS = Map.of(
        "bell", emeraldsTo256ths(2),
        "name_tag", emeraldsTo256ths(1),
        "globe_banner_pattern", emeraldsTo256ths(1),
        "iron_chestplate", emeraldsTo256ths(5),
        "leather_chestplate", emeraldsTo256ths(3),
        "ender_pearl", emeraldsTo256ths(2),
        "item_frame", 137, // 0.4 emerald: 5 → 2
        "map", 171,        // 0.5 emerald: 2 → 1
        "blue_ice", 103);  // 0.3 emerald: 7 → 2, the nearest row the tolerance allows

    /** Every effect potion trades for at least 1 emerald. */
    static final int POTION_BASE_EMERALDS = 1;

    /**
     * Cap on the amplifier bonus so a command-given Strength CCLV can't sell for
     * hundreds of emeralds (and stays within the 22-per-emerald floor range).
     */
    static final int MAX_POTION_AMPLIFIER_BONUS = 9;

    /** Vanilla's naming for the extended-duration variants ({@code long_swiftness}). */
    private static final String EXTENDED_POTION_PREFIX = "long_";

    /** +1 permanent backpack slot — 5 emeralds. */
    private static final int EDIBLE_BACKPACK_VALUE_SIXTEENTHS = 80;

    /** +9 slots, a 3×3 of edible backpacks — 45 emeralds, i.e. 9 × the plain one. */
    private static final int GOLDEN_EDIBLE_BACKPACK_VALUE_SIXTEENTHS = 720;

    /** Disposable camera (Polaroid's instant camera) — pays out 2 emeralds after the margin. */
    private static final int INSTANT_CAMERA_VALUE_SIXTEENTHS = EMERALD_PAYOUT_SIXTEENTHS * 2;

    /** Exposure photographs trade like a written book. */
    private static final List<String> PHOTOGRAPH_IDS = List.of(
        "exposure:photograph", "exposure:aged_photograph");

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
        // FIRST: the community-approved values bundled from the relay's item-values page
        // (TradeValueTable). TE takes the first non-empty provider answer, so an approved
        // value beats every hand-tuned constant below and TE's own defaults. Per item id —
        // a plain lookup, no stack inspection.
        TradeEverythingApi.registerValueProvider(stack ->
            stack.isEmpty() ? OptionalInt.empty()
                : TradeValueTable.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));

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

        // Tag-matched rather than 17 item overrides: a provider also beats TE's
        // recipe derivation (which would price a template off the 7-diamond
        // duplication recipe), and a datapack-added trim template is covered too.
        // The tag excludes netherite_upgrade_smithing_template — not an armor trim.
        TradeEverythingApi.registerValueProvider(stack ->
            stack.is(ItemTags.TRIM_TEMPLATES)
                ? OptionalInt.of(TRIM_TEMPLATE_VALUE_SIXTEENTHS)
                : OptionalInt.empty());

        // Brewing isn't a crafting recipe and potions are COMMON, so TE would
        // floor every potion at 1 sixteenth — Strength II priced like a stick.
        TradeEverythingApi.registerValueProvider(TradeEverythingBridge::potionValue);

        TradeEverythingApi.setItemOverride256ths(
            ResourceLocation.withDefaultNamespace("bookshelf"), BOOKSHELF_VALUE_256THS);
        TradeEverythingApi.setItemOverride(
            ResourceLocation.withDefaultNamespace("honey_block"), HONEY_BLOCK_VALUE_SIXTEENTHS);
        VILLAGER_STOCK_VALUES_256THS.forEach((path, value) ->
            TradeEverythingApi.setItemOverride256ths(ResourceLocation.withDefaultNamespace(path), value));

        // Sibling-mod items, addressed by id so the sibling is never classloaded:
        // absent EdibleBackpacks simply means the override never matches.
        TradeEverythingApi.setItemOverride(
            ResourceLocation.fromNamespaceAndPath("ediblebackpacks", "edible_backpack"),
            EDIBLE_BACKPACK_VALUE_SIXTEENTHS);
        TradeEverythingApi.setItemOverride(
            ResourceLocation.fromNamespaceAndPath("ediblebackpacks", "golden_edible_backpack"),
            GOLDEN_EDIBLE_BACKPACK_VALUE_SIXTEENTHS);
        TradeEverythingApi.setItemOverride(
            ResourceLocation.parse(DisabledModContent.INSTANT_CAMERA), INSTANT_CAMERA_VALUE_SIXTEENTHS);
        PHOTOGRAPH_IDS.forEach(id ->
            TradeEverythingApi.setItemOverride(ResourceLocation.parse(id), WRITTEN_BOOK_VALUE_SIXTEENTHS));
    }

    /**
     * Whether {@code install()} prices this item by hand (a constant above, or the trim-template
     * tag) rather than leaving it to TE — what the catalog dump labels {@code dt-bridge}. Potions
     * and written books are stack-dependent and reported separately as {@code dynamic}.
     */
    static boolean isHandTuned(ResourceLocation id, ItemStack stack) {
        if (stack.is(ItemTags.TRIM_TEMPLATES)) return true;
        if (ResourceLocation.DEFAULT_NAMESPACE.equals(id.getNamespace())
            && VILLAGER_STOCK_VALUES_256THS.containsKey(id.getPath())) {
            return true;
        }
        String s = id.toString();
        return s.equals("minecraft:bookshelf") || s.equals("minecraft:honey_block")
            || s.equals("ediblebackpacks:edible_backpack") || s.equals("ediblebackpacks:golden_edible_backpack")
            || s.equals(DisabledModContent.INSTANT_CAMERA) || PHOTOGRAPH_IDS.contains(s);
    }

    /**
     * Drinkable, splash and lingering potions carrying at least one effect.
     * Effect-less bottles (water, awkward, mundane, thick) and tipped arrows
     * (8 per lingering potion) fall through to TE's default valuation.
     */
    private static OptionalInt potionValue(ItemStack stack) {
        boolean lingering = stack.is(Items.LINGERING_POTION);
        if (!lingering && !stack.is(Items.POTION) && !stack.is(Items.SPLASH_POTION)) {
            return OptionalInt.empty();
        }
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null || !contents.hasEffects()) return OptionalInt.empty();

        int maxAmplifier = 0;
        for (var effect : contents.getAllEffects()) {
            maxAmplifier = Math.max(maxAmplifier, effect.getAmplifier());
        }
        boolean extended = contents.potion()
            .flatMap(holder -> holder.unwrapKey())
            .map(key -> key.location().getPath().startsWith(EXTENDED_POTION_PREFIX))
            .orElse(false);
        return OptionalInt.of(emeraldsToSixteenths(potionEmeralds(maxAmplifier, extended, lingering)));
    }

    /**
     * Whether TE should pay {@code stack} out in emeralds rather than the
     * villager's goods — see {@code TradePricerEmeraldPayoutMixin}. Its price is
     * set in emeralds, so goods would turn "1 emerald" into ~20 wheat.
     */
    public static boolean paysInEmeralds(ItemStack stack) {
        return potionValue(stack).isPresent();
    }

    /**
     * Emeralds a potion pays out: 1, plus one per amplifier level of its
     * strongest effect, plus one for the extended variant, plus one for the
     * lingering form (it costs dragon's breath).
     */
    static int potionEmeralds(int maxAmplifier, boolean extended, boolean lingering) {
        return POTION_BASE_EMERALDS
            + Math.clamp(maxAmplifier, 0, MAX_POTION_AMPLIFIER_BONUS)
            + (extended ? 1 : 0)
            + (lingering ? 1 : 0);
    }

    static int emeraldsToSixteenths(int emeralds) {
        return EMERALD_PAYOUT_SIXTEENTHS * emeralds;
    }

    /** {@link #emeraldsToSixteenths} in 256ths, for {@link #VILLAGER_STOCK_VALUES_256THS}. */
    static int emeraldsTo256ths(int emeralds) {
        return emeraldsToSixteenths(emeralds) * SIXTEENTH_IN_256THS;
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
