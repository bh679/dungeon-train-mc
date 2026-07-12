package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.item.VariantClipboardItem;
import games.brennan.dungeontrain.platform.DtRegistrar;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Mod-side item registry. Registered via {@link DtRegistrar} (loader-neutral)
 * instead of a direct {@code DeferredRegister} — see
 * {@link games.brennan.dungeontrain.advancement.ModAdvancementTriggers} for
 * the pattern and the root attach timing.
 *
 * <p>Currently registers only {@link VariantClipboardItem}, the per-cell
 * variant snippet produced by the block-variant menu's Copy button. Prefab
 * tab entries are vanilla {@code BlockItem} stacks with discriminator NBT
 * (see {@link games.brennan.dungeontrain.event.PrefabUseHandler}) — no
 * mod-side item needed for those.</p>
 *
 * <p>The variant clipboard is hooked into the Creative inventory's
 * TOOLS_AND_UTILITIES tab via the loader-neutral
 * {@code DtEvents.BUILD_CREATIVE_TAB_CONTENTS} declarative registration (see
 * {@link #onBuildCreativeTabs}), registered from
 * {@code DungeonTrainCommon.init()}.</p>
 */
public final class ModItems {

    public static final Supplier<Item> VARIANT_CLIPBOARD = DtRegistrar.get().register(
        Registries.ITEM,
        "variant_clipboard",
        () -> new VariantClipboardItem(new Item.Properties().stacksTo(1))
    );

    /**
     * Editor-only placeholder. Never appears in survival inventories — at
     * chest spawn time {@code ContainerContentsRoller.rollItemStack}
     * intercepts entries with this item id and substitutes a stamped vanilla
     * {@code WRITTEN_BOOK} rolled from
     * {@link games.brennan.dungeontrain.narrative.RandomBookRegistry}.
     */
    public static final Supplier<Item> RANDOM_BOOK = DtRegistrar.get().register(
        Registries.ITEM,
        "random_book",
        () -> new Item(new Item.Properties().stacksTo(1))
    );

    private ModItems() {}

    /** Call from the mod constructor to force this class's static fields (and their registrations) to run. */
    public static void init() {}

    /**
     * Add the variant clipboard to the Creative inventory's TOOLS_AND_UTILITIES
     * tab. The narrative-side {@link #RANDOM_BOOK} placeholder lives in the
     * mod's own NARRATIVE tab (see {@link ModCreativeTabs#NARRATIVE}), not
     * here — narrative authoring deserves its own grouping rather than being
     * mixed in with editor tools.
     */
    public static void onBuildCreativeTabs(ResourceKey<CreativeModeTab> tabKey, Consumer<ItemStack> output) {
        if (tabKey == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            output.accept(new ItemStack(VARIANT_CLIPBOARD.get()));
        }
    }
}
