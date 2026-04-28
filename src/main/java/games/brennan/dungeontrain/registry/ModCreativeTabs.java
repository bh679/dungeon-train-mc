package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.PrefabTabState;
import games.brennan.dungeontrain.event.PrefabUseHandler;
import games.brennan.dungeontrain.net.PrefabRegistrySyncPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Custom {@link CreativeModeTab}s for prefab browsing. Each tab is populated
 * from {@link PrefabTabState} (synced on login) — entries are vanilla
 * {@code BlockItem} stacks (oak_planks, chest, barrel, etc.) so the icon
 * matches the source block exactly. A discriminator NBT tag
 * ({@link PrefabUseHandler#NBT_BV_PREFAB_ID} or
 * {@link PrefabUseHandler#NBT_LOOT_PREFAB_ID}) tells the use handler how to
 * interpret the stack on right-click.
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
                for (PrefabRegistrySyncPacket.Entry entry : PrefabTabState.variantEntries()) {
                    ItemStack stack = buildPrefabStack(
                        entry.blockId(), Items.COMMAND_BLOCK,
                        PrefabUseHandler.NBT_BV_PREFAB_ID, entry.id(), entry.committed());
                    output.accept(stack);
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
                for (PrefabRegistrySyncPacket.Entry entry : PrefabTabState.lootEntries()) {
                    ItemStack stack = buildPrefabStack(
                        entry.blockId(), Items.CHEST,
                        PrefabUseHandler.NBT_LOOT_PREFAB_ID, entry.id(), entry.committed());
                    output.accept(stack);
                }
            })
            .build()
    );

    private ModCreativeTabs() {}

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }

    /**
     * Build a vanilla {@code BlockItem} stack for {@code blockIdString} with
     * an NBT discriminator the use handler can read. Falls back to
     * {@code fallbackItem} if the block id doesn't resolve to a real item
     * (e.g. blocks with no item form). When {@code committed} is false the
     * stack also gets {@link PrefabUseHandler#NBT_PREFAB_UNCOMMITTED} so the
     * slot mixin renders a tinted background — visual cue that the prefab
     * exists only in user config and hasn't been promoted to the source tree.
     */
    private static ItemStack buildPrefabStack(String blockIdString, Item fallbackItem,
                                              String nbtKey, String prefabId, boolean committed) {
        ResourceLocation rl = ResourceLocation.tryParse(blockIdString);
        Item item = fallbackItem;
        if (rl != null) {
            Block block = BuiltInRegistries.BLOCK.get(rl);
            if (block != null) {
                Item resolved = block.asItem();
                if (resolved != Items.AIR) item = resolved;
            }
        }
        ItemStack stack = new ItemStack(item);
        CompoundTag tag = new CompoundTag();
        tag.putString(nbtKey, prefabId);
        if (!committed) tag.putBoolean(PrefabUseHandler.NBT_PREFAB_UNCOMMITTED, true);
        stack.setTag(tag);
        return stack;
    }
}
