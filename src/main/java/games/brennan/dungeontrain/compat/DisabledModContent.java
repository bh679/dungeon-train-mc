package games.brennan.dungeontrain.compat;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The armour, weapons, tools, gear metals and ores of the biome mods DT depends on — Biomes O' Plenty, BetterEnd and
 * BetterNether. DT keeps their biomes and blocks but not their parallel gear progression, which would
 * bypass DT's own loot and difficulty balance. Also Exposure's original camera, film and lightroom
 * ({@link #HIDDEN_ITEMS}), replaced by the Polaroid add-on's instant camera, and Vista's Viewfinder, Wave Gate and
 * cassettes — DT's Television shows only the Live Feed — plus Vista's Picture Tape recipe ({@link #DISABLED_RECIPES}),
 * though the tape itself is kept. And every non-vanilla enchantment
 * ({@link #isDisabledEnchantment}) — today BetterNether's Obsidian Breaker and Ruby Fire and BetterEnd's
 * Resonance and End Veil — kept out of the enchanting table and villager books by the {@code remove} lists in
 * {@code data/minecraft/tags/enchantment/}, and stripped from loot and creative here.
 *
 * <p>Single source of truth for five enforcement points: recipes ({@code RecipeManagerDisableMixin}),
 * loot ({@link StripDisabledItemsLootModifier}), ore placement ({@code ChunkGeneratorDecorationMixin})
 * the creative tabs and spawned mobs' equipment ({@link DisabledModMobGear}). Gear is recognised by item class, so hammers, excavators and anything a mod
 * update adds are caught without a list; ores, metals and BetterEnd's tool parts are recognised by id.</p>
 */
public final class DisabledModContent {

    /** Mods whose gear and ores are disabled. BoP ships none today — it is covered for future updates. */
    static final Set<String> NAMESPACES = Set.of("betternether", "betterend", "biomesoplenty");

    /** Mods whose own creative tabs are hidden by default: the ones above, plus VanillaBackport — its gear is
     *  kept — and Exposure with its Polaroid add-on, whose one item players get is DT's own disposable camera. */
    private static final Set<String> HIDDEN_TAB_NAMESPACES =
        Set.of("betternether", "betterend", "biomesoplenty", "vanillabackport", "exposure", "exposure_polaroid");

    /** Ore blocks and ore placed features: {@code cincinnasite_ore}, {@code nether_ruby_large_ore}, … */
    private static final Pattern ORE = Pattern.compile("(?:^|.*_)ore$");

    /** BetterEnd's smithing parts ({@code terminite_axe_head}, {@code aeternium_sword_blade}, …) — dead
     *  intermediates once the tools they make are gone. */
    private static final Pattern TOOL_PART =
        Pattern.compile(".*_(?:axe|hammer|hoe|pickaxe|shovel)_head$|.*_sword_(?:blade|handle)$");

    /** Gear metals: ingots, nuggets and raw ore drops ({@code cincinnasite_ingot}, {@code thallasium_nugget},
     *  {@code raw_amber}). Gems (nether ruby, amber) are kept — decorative blocks are made from them. */
    private static final Pattern METAL = Pattern.compile(".*_(?:ingot|nugget)$|^raw_.*");

    /** Smithing templates — the mods' templates only upgrade or assemble their gear. */
    private static final Pattern SMITHING_TEMPLATE = Pattern.compile(".*_smithing_template$");

    /** Templates kept because they upgrade decor, not gear (BetterNether's fire bowls). */
    private static final Set<String> KEPT_TEMPLATES = Set.of("betternether:bowl_upgrade_smithing_template");

    /**
     * Exposure's original film workflow — the camera, its film rolls (fresh and developed), the lightroom
     * and the chromatic sheet it prints. Players get Exposure: Polaroid's instant camera instead, which
     * needs none of them; Exposure itself stays installed because the add-on runs on it.
     *
     * <p>Also the add-on's four instant slides. Players never hold a plain instant camera — only
     * {@link DisposableCamera}, which comes with its slide loaded as data and can't be topped up — so
     * the slide items have nothing to feed and are hidden with their recipes.</p>
     *
     * <p>And Vista's Viewfinder, Wave Gate, pre-recorded Cassette and Hollow Cassette: a Television's only job in
     * DT is the Live Feed, which it tunes to by itself. Hiding the two cassettes also strips Vista's own chest and
     * creeper tape drops ({@code vista:inject/*}), which arrive through the same loot tables. Mirrors and the
     * music disc are left as Vista ships them; Picture Tape is too, except that it can't be crafted
     * ({@link #DISABLED_RECIPES}).</p>
     */
    static final Set<String> HIDDEN_ITEMS = Set.of(
        "exposure:camera",
        "exposure:black_and_white_film",
        "exposure:color_film",
        "exposure:high_sensitivity_black_and_white_film",
        "exposure:high_sensitivity_color_film",
        "exposure:developed_black_and_white_film",
        "exposure:developed_color_film",
        "exposure:lightroom",
        "exposure:chromatic_sheet",
        "exposure_polaroid:instant_color_slide",
        "exposure_polaroid:instant_black_and_white_slide",
        "exposure_polaroid:high_sensitivity_instant_color_slide",
        "exposure_polaroid:high_sensitivity_instant_black_and_white_slide",
        "vista:viewfinder",
        "vista:wave_gate",
        "vista:cassette",
        "vista:hollow_cassette");

    static final String INSTANT_CAMERA = "exposure_polaroid:instant_camera";

    /**
     * Recipes dropped by id while the item they make is kept — it stays in creative, loot and anywhere else it
     * already turns up. Vista's Picture Tape: players can no longer craft one.
     */
    static final Set<String> DISABLED_RECIPES = Set.of("vista:picture_tape");

    private DisabledModContent() {}

    /** A recipe turned off by id ({@link #DISABLED_RECIPES}), whatever it makes. */
    public static boolean isDisabledRecipe(ResourceLocation recipeId) {
        return recipeId != null && DISABLED_RECIPES.contains(recipeId.toString());
    }

    /** A disabled mod's ore placed feature — vetoed at decoration time. */
    public static boolean isDisabledOreFeature(ResourceLocation placedFeature) {
        return placedFeature != null
            && NAMESPACES.contains(placedFeature.getNamespace())
            && ORE.matcher(placedFeature.getPath()).matches();
    }

    /** A biome mod's own creative tab ({@code betternether:blocks_tab}, {@code biomesoplenty:main},
     *  {@code vanillabackport:vanilla_backport}, …). Hidden, and kept out of the creative search, unless
     *  the client's two creative settings say otherwise — see {@link DisabledModCreativeTabs}. */
    public static boolean isBiomeModCreativeTab(ResourceLocation tabId) {
        return tabId != null && HIDDEN_TAB_NAMESPACES.contains(tabId.getNamespace());
    }

    /** True for a disabled mod's armour, weapon, tool, tool part, gear smithing template, metal or ore item. */
    public static boolean isDisabledItem(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return isDisabledItem(id, isGearClass(item));
    }

    public static boolean isDisabledItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (isPlainInstantCamera(id, DisposableCamera.hasMarker(stack, DisposableCamera.NBT_CAMERA))) {
            return true;
        }
        if (stack.is(Items.ENCHANTED_BOOK) && storesNoKeptEnchantment(stack.get(DataComponents.STORED_ENCHANTMENTS))) {
            return true;
        }
        return isDisabledItem(stack.getItem());
    }

    /** Any enchantment a mod adds — vanilla's {@code minecraft:} ones are the only ones DT keeps. */
    public static boolean isDisabledEnchantment(ResourceLocation id) {
        return id != null && !ResourceLocation.DEFAULT_NAMESPACE.equals(id.getNamespace());
    }

    private static boolean isDisabledEnchantment(Holder<Enchantment> enchantment) {
        return enchantment.unwrapKey().map(key -> isDisabledEnchantment(key.location())).orElse(false);
    }

    /**
     * {@code stack} without its disabled enchantments, applied or stored — a new copy, or {@code stack}
     * itself when it carries none. A book left with nothing stored is caught by {@link #isDisabledItem(ItemStack)}.
     */
    public static ItemStack stripDisabledEnchantments(ItemStack stack) {
        if (!hasDisabled(stack.get(DataComponents.ENCHANTMENTS))
            && !hasDisabled(stack.get(DataComponents.STORED_ENCHANTMENTS))) {
            return stack;
        }
        ItemStack copy = stack.copy();
        strip(copy, DataComponents.ENCHANTMENTS);
        strip(copy, DataComponents.STORED_ENCHANTMENTS);
        return copy;
    }

    private static void strip(ItemStack stack, DataComponentType<ItemEnchantments> type) {
        ItemEnchantments enchantments = stack.get(type);
        if (!hasDisabled(enchantments)) {
            return;
        }
        ItemEnchantments.Mutable kept = new ItemEnchantments.Mutable(enchantments);
        kept.removeIf(DisabledModContent::isDisabledEnchantment);
        stack.set(type, kept.toImmutable());
    }

    private static boolean hasDisabled(ItemEnchantments enchantments) {
        return enchantments != null
            && enchantments.keySet().stream().anyMatch(DisabledModContent::isDisabledEnchantment);
    }

    /** True when a book stores only disabled enchantments, or none left after stripping — nothing worth keeping. */
    private static boolean storesNoKeptEnchantment(ItemEnchantments stored) {
        return stored == null
            || stored.keySet().stream().allMatch(DisabledModContent::isDisabledEnchantment);
    }

    /**
     * Polaroid's reloadable instant camera is hidden; the {@link DisposableCamera} is the same item
     * carrying DT's marker, so this one rule has to look at the stack rather than the item.
     */
    static boolean isPlainInstantCamera(ResourceLocation id, boolean disposable) {
        return id != null && INSTANT_CAMERA.equals(id.toString()) && !disposable;
    }

    /** Id-level rule, split out so it is testable without a bootstrapped registry. */
    static boolean isDisabledItem(ResourceLocation id, boolean gearClass) {
        if (id == null) {
            return false;
        }
        if (HIDDEN_ITEMS.contains(id.toString())) {
            return true;
        }
        if (!NAMESPACES.contains(id.getNamespace())) {
            return false;
        }
        String path = id.getPath();
        if (SMITHING_TEMPLATE.matcher(path).matches()) {
            return !KEPT_TEMPLATES.contains(id.toString());
        }
        return gearClass
            || ORE.matcher(path).matches()
            || METAL.matcher(path).matches()
            || TOOL_PART.matcher(path).matches();
    }

    /** Armour, melee weapons (swords, maces, tridents), ranged weapons, shields and every tiered tool. */
    private static boolean isGearClass(Item item) {
        return item instanceof ArmorItem
            || item instanceof TieredItem
            || item instanceof ElytraItem
            || item instanceof ShieldItem
            || item instanceof ProjectileWeaponItem
            || item instanceof TridentItem
            || item instanceof MaceItem;
    }
}
