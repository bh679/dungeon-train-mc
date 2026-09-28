package games.brennan.dungeontrain.compat;

import games.brennan.tradeeverything.api.TradeEverythingApi;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

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
     * Bookshelf and honey block — trade for 1 emerald each. 22 sixteenths × the
     * default 0.75 payout margin = 16.5, so the payout clears exactly 1 emerald
     * (16 would pay out only 12 sixteenths). Recipe derivation priced a
     * bookshelf at ~8 emeralds, far too much for a block library carriages are
     * packed with.
     */
    private static final int BOOKSHELF_VALUE_SIXTEENTHS = 22;
    private static final int HONEY_BLOCK_VALUE_SIXTEENTHS = 22;

    /**
     * Value that pays out exactly one emerald per multiple — the same 22 as the
     * bookshelf. {@code 22n × 0.75 = 16.5n} floors to n emeralds for every n < 32.
     */
    static final int EMERALD_PAYOUT_SIXTEENTHS = 22;

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

        TradeEverythingApi.setItemOverride(
            ResourceLocation.withDefaultNamespace("bookshelf"), BOOKSHELF_VALUE_SIXTEENTHS);
        TradeEverythingApi.setItemOverride(
            ResourceLocation.withDefaultNamespace("honey_block"), HONEY_BLOCK_VALUE_SIXTEENTHS);

        // Sibling-mod items, addressed by id so the sibling is never classloaded:
        // absent EdibleBackpacks simply means the override never matches.
        TradeEverythingApi.setItemOverride(
            ResourceLocation.fromNamespaceAndPath("ediblebackpacks", "edible_backpack"),
            EDIBLE_BACKPACK_VALUE_SIXTEENTHS);
        TradeEverythingApi.setItemOverride(
            ResourceLocation.fromNamespaceAndPath("ediblebackpacks", "golden_edible_backpack"),
            GOLDEN_EDIBLE_BACKPACK_VALUE_SIXTEENTHS);
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

    /** See {@link #OMINOUS_BANNER_NAME_KEY} for why the check is component-based. */
    private static boolean isOminousBanner(ItemStack stack) {
        if (!stack.is(ItemTags.BANNERS)) return false;
        Component name = stack.get(DataComponents.ITEM_NAME);
        return name != null
            && name.getContents() instanceof TranslatableContents translatable
            && OMINOUS_BANNER_NAME_KEY.equals(translatable.getKey());
    }
}
