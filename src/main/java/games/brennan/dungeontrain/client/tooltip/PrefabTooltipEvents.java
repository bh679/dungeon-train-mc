package games.brennan.dungeontrain.client.tooltip;

import com.mojang.datafixers.util.Either;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.PrefabTabState;
import games.brennan.dungeontrain.event.PrefabUseHandler;
import games.brennan.dungeontrain.net.PrefabRegistrySyncPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Wires the prefab icon-grid tooltip into vanilla's tooltip pipeline:
 *
 * <ul>
 *   <li>{@link ForgeBus#onGatherComponents} — forge bus, client side. Reads
 *       the prefab discriminator NBT (set by {@link PrefabUseHandler#NBT_BV_PREFAB_ID}
 *       / {@link PrefabUseHandler#NBT_LOOT_PREFAB_ID}), looks up the cached
 *       contents in {@link PrefabTabState}, and appends a
 *       {@link PrefabIconsTooltipData} so vanilla layout reserves space for
 *       the icon grid below the existing text rows.</li>
 *   <li>{@link ModBus#onRegisterFactories} — mod bus, client side. Maps the
 *       data type to {@link PrefabIconsClientTooltipComponent} so vanilla can
 *       render it.</li>
 * </ul>
 *
 * <p>Resolved icon stacks are cached per prefab id so the per-frame tooltip
 * gather doesn't re-do the registry lookups; the cache is invalidated whenever
 * the registry sync packet refreshes the client state.</p>
 */
public final class PrefabTooltipEvents {

    private PrefabTooltipEvents() {}

    @Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {

        private ModBus() {}

        @SubscribeEvent
        public static void onRegisterFactories(RegisterClientTooltipComponentFactoriesEvent event) {
            event.register(PrefabIconsTooltipData.class, PrefabIconsClientTooltipComponent::new);
        }
    }

    @Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeBus {

        private ForgeBus() {}

        @SubscribeEvent
        public static void onGatherComponents(RenderTooltipEvent.GatherComponents event) {
            ItemStack stack = event.getItemStack();
            if (stack.isEmpty()) return;
            CompoundTag tag = stack.getTag();
            if (tag == null) return;

            PrefabIconsTooltipData data = null;
            if (tag.contains(PrefabUseHandler.NBT_BV_PREFAB_ID, Tag.TAG_STRING)) {
                data = buildVariantData(tag.getString(PrefabUseHandler.NBT_BV_PREFAB_ID));
            } else if (tag.contains(PrefabUseHandler.NBT_LOOT_PREFAB_ID, Tag.TAG_STRING)) {
                data = buildLootData(tag.getString(PrefabUseHandler.NBT_LOOT_PREFAB_ID));
            }
            if (data == null) return;
            event.getTooltipElements().add(Either.right(data));
        }
    }

    /**
     * Build the icon row for a block-variant prefab — one icon per
     * {@code VariantState} (duplicate icons reflect the weighted-alternates
     * design). Air sentinels and unresolved blocks are skipped.
     */
    private static PrefabIconsTooltipData buildVariantData(String prefabId) {
        Optional<List<String>> blocks = PrefabTabState.findVariantBlocks(prefabId);
        if (blocks.isEmpty()) return null;
        List<String> all = blocks.get();
        int total = all.size();
        int cap = Math.min(total, PrefabIconsClientTooltipComponent.MAX_ICONS);
        List<ItemStack> stacks = new ArrayList<>(cap);
        for (int i = 0; i < cap; i++) {
            ItemStack s = blockStackFromId(all.get(i));
            if (!s.isEmpty()) stacks.add(s);
        }
        if (stacks.isEmpty()) return null;
        return new PrefabIconsTooltipData(stacks, total);
    }

    /**
     * Build the icon row for a loot prefab — one icon per
     * {@code ContainerContentsEntry} with the entry's count baked into the
     * stack so vanilla's renderer draws the count badge. Unresolved item ids
     * are skipped.
     */
    private static PrefabIconsTooltipData buildLootData(String prefabId) {
        Optional<List<PrefabRegistrySyncPacket.LootItem>> items =
            PrefabTabState.findLootItems(prefabId);
        if (items.isEmpty()) return null;
        List<PrefabRegistrySyncPacket.LootItem> all = items.get();
        int total = all.size();
        int cap = Math.min(total, PrefabIconsClientTooltipComponent.MAX_ICONS);
        List<ItemStack> stacks = new ArrayList<>(cap);
        for (int i = 0; i < cap; i++) {
            PrefabRegistrySyncPacket.LootItem item = all.get(i);
            ItemStack s = itemStackFromId(item.itemId(), item.count());
            if (!s.isEmpty()) stacks.add(s);
        }
        if (stacks.isEmpty()) return null;
        return new PrefabIconsTooltipData(stacks, total);
    }

    private static ItemStack blockStackFromId(String blockId) {
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        if (rl == null) return ItemStack.EMPTY;
        Block block = BuiltInRegistries.BLOCK.get(rl);
        if (block == null) return ItemStack.EMPTY;
        Item item = block.asItem();
        if (item == Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(item);
    }

    private static ItemStack itemStackFromId(String itemId, int count) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == null || item == Items.AIR) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item);
        stack.setCount(Math.max(1, Math.min(count, item.getMaxStackSize())));
        return stack;
    }
}
