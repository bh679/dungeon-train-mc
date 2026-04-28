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
 * Mod-side item registry. The first {@link DeferredRegister} in the
 * project — wires up custom items via the mod-event bus during
 * construction. Currently registers only {@link VariantClipboardItem}, the
 * command-block-skinned hotbar item produced by the block-variant menu's
 * Copy action.
 *
 * <p>Hooked into the Creative inventory's MISC tab via
 * {@link BuildCreativeModeTabContentsEvent} so authors can manually pull a
 * blank clipboard for testing without going through the menu's Copy
 * button.</p>
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
     * Add the clipboard to the Creative inventory's MISC tab. Allows
     * authors to spawn a blank one for testing without the menu's Copy
     * action — a no-NBT clipboard placed in the world creates an empty
     * variant cell at the target position (which the player can then add
     * variants to via the menu's Add button).
     */
    @SubscribeEvent
    public static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(VARIANT_CLIPBOARD.get());
        }
    }
}
