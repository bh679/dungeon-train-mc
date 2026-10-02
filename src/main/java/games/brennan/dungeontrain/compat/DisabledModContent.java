package games.brennan.dungeontrain.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The armour, weapons, tools, gear metals and ores of the biome mods DT depends on — Biomes O' Plenty, BetterEnd and
 * BetterNether. DT keeps their biomes and blocks but not their parallel gear progression, which would
 * bypass DT's own loot and difficulty balance. Also Exposure's original camera, film and lightroom
 * ({@link #HIDDEN_ITEMS}), replaced by the Polaroid add-on's instant camera.
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
        "exposure:chromatic_sheet");

    static final String INSTANT_CAMERA = "exposure_polaroid:instant_camera";

    private DisabledModContent() {}

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
        return isDisabledItem(stack.getItem());
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
