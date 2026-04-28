package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.item.VariantClipboardItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Mod-side item registry. Wires up custom items via the mod-event bus during
 * construction.
 *
 * <p>Currently registers only {@link VariantClipboardItem}, the per-cell
 * variant snippet produced by the block-variant menu's Copy button. Prefab
 * tab entries are vanilla {@code BlockItem} stacks with discriminator NBT
 * (see {@link games.brennan.dungeontrain.event.PrefabUseHandler}) — no
 * mod-side item needed for those.</p>
 *
 * <p>The variant clipboard is hooked into the Creative inventory's
 * TOOLS_AND_UTILITIES tab via {@link BuildCreativeModeTabContentsEvent} so
 * authors can grab a blank clipboard for testing.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ModItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(DungeonTrain.MOD_ID);

    public static final DeferredItem<Item> VARIANT_CLIPBOARD = ITEMS.register(
        "variant_clipboard",
        () -> new VariantClipboardItem(new Item.Properties().stacksTo(1))
    );

    private ModItems() {}

    /** Call from the mod constructor to attach the {@link DeferredRegister} to the mod-event bus. */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    /**
     * Add the variant clipboard to the Creative inventory's MISC tab. Allows
     * authors to spawn a blank one for testing without the menu's Copy
     * action.
     */
    @SubscribeEvent
    public static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(VARIANT_CLIPBOARD.get());
        }
    }
}
