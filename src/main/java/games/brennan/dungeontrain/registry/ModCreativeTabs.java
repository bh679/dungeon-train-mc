package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.PrefabTabState;
import games.brennan.dungeontrain.item.BlockVariantPrefabItem;
import games.brennan.dungeontrain.item.LootPrefabItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registers two custom {@link CreativeModeTab}s for prefab browsing —
 * {@link #PREFAB_VARIANTS} and {@link #PREFAB_LOOT}. The tabs are real
 * vanilla creative tabs: vanilla handles all rendering (items grid, title,
 * scrollbar, tooltips, scroll wheel) end-to-end. Side-tab buttons added by
 * the mixin call {@code selectTab} to switch into these.
 *
 * <p>Tab content is dynamic — populated from {@link PrefabTabState}, which
 * the {@link games.brennan.dungeontrain.net.PrefabRegistrySyncPacket}
 * updates on player login. The {@code displayItems} lambda runs at
 * {@code tryRebuildTabContents} time, so the sync handler must trigger a
 * rebuild after updating state.</p>
 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(
        Registries.CREATIVE_MODE_TAB, DungeonTrain.MOD_ID);

    public static final RegistryObject<CreativeModeTab> PREFAB_VARIANTS = TABS.register(
        "prefab_variants",
        () -> CreativeModeTab.builder()
            .title(Component.translatable("gui.dungeontrain.prefab_tab.variants"))
            .icon(() -> new ItemStack(Items.COMMAND_BLOCK))
            .displayItems((parameters, output) -> {
                for (String id : PrefabTabState.variantIds()) {
                    output.accept(BlockVariantPrefabItem.stackForPrefab(
                        ModItems.BLOCK_VARIANT_PREFAB.get(), id));
                }
            })
            .build()
    );

    public static final RegistryObject<CreativeModeTab> PREFAB_LOOT = TABS.register(
        "prefab_loot",
        () -> CreativeModeTab.builder()
            .title(Component.translatable("gui.dungeontrain.prefab_tab.loot"))
            .icon(() -> new ItemStack(Items.CHEST))
            .displayItems((parameters, output) -> {
                for (String id : PrefabTabState.contentsIds()) {
                    output.accept(LootPrefabItem.stackForPrefab(
                        ModItems.LOOT_PREFAB.get(), id));
                }
            })
            .build()
    );

    private ModCreativeTabs() {}

    /** Call from the mod constructor to attach the registry to the mod-event bus. */
    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
