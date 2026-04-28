package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.item.BlockVariantPrefabItem;
import games.brennan.dungeontrain.item.LootPrefabItem;
import games.brennan.dungeontrain.item.VariantClipboardItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Mod-side item registry. Wires up custom items via the mod-event bus during
 * construction.
 *
 * <ul>
 *   <li>{@link VariantClipboardItem} — per-cell variant snippet produced by
 *       the block-variant menu's Copy button.</li>
 *   <li>{@link BlockVariantPrefabItem} — id-tagged clipboard for a named
 *       block-variant prefab from the prefab library. Sourced via the
 *       left-side Variants tab in the creative menu.</li>
 *   <li>{@link LootPrefabItem} — id-tagged clipboard for a named per-container
 *       loot prefab. Sourced via the left-side Loot tab.</li>
 * </ul>
 *
 * <p>The variant clipboard is hooked into the Creative inventory's
 * TOOLS_AND_UTILITIES tab via {@link BuildCreativeModeTabContentsEvent} so
 * authors can grab a blank clipboard for testing. The prefab items appear in
 * the custom left-side prefab tabs only — they require an id-tagged stack
 * to do anything useful.</p>
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(
        ForgeRegistries.ITEMS, DungeonTrain.MOD_ID);

    public static final RegistryObject<Item> VARIANT_CLIPBOARD = ITEMS.register(
        "variant_clipboard",
        () -> new VariantClipboardItem(new Item.Properties().stacksTo(1))
    );

    public static final RegistryObject<Item> BLOCK_VARIANT_PREFAB = ITEMS.register(
        "block_variant_prefab",
        () -> new BlockVariantPrefabItem(new Item.Properties().stacksTo(1))
    );

    public static final RegistryObject<Item> LOOT_PREFAB = ITEMS.register(
        "loot_prefab",
        () -> new LootPrefabItem(new Item.Properties().stacksTo(1))
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
