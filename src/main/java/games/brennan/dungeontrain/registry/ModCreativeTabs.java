package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.client.menu.PrefabTabState;
import games.brennan.dungeontrain.editor.LootPrefabStore;
import games.brennan.dungeontrain.event.PrefabUseHandler;
import games.brennan.dungeontrain.net.PrefabRegistrySyncPacket;
import games.brennan.dungeontrain.platform.DtRegistrar;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.function.Supplier;

/**
 * Custom {@link CreativeModeTab}s for prefab browsing. Each tab is populated
 * from {@link PrefabTabState} (synced on login) — entries are vanilla
 * {@code BlockItem} stacks (oak_planks, chest, barrel, etc.) so the icon
 * matches the source block exactly. A discriminator NBT tag
 * ({@link PrefabUseHandler#NBT_BV_PREFAB_ID} or
 * {@link PrefabUseHandler#NBT_LOOT_PREFAB_ID}) tells the use handler how to
 * interpret the stack on right-click.
 *
 * <p>Registered via {@link DtRegistrar} (loader-neutral) instead of a direct
 * {@code DeferredRegister} — see
 * {@link games.brennan.dungeontrain.advancement.ModAdvancementTriggers} for
 * the pattern and the root attach timing.</p>
 */
public final class ModCreativeTabs {

    public static final Supplier<CreativeModeTab> PREFAB_VARIANTS = register(
        "prefab_variants",
        () -> CreativeModeTab.builder()
            .title(Component.translatable("gui.dungeontrain.prefab_tab.variants"))
            .icon(() -> new ItemStack(Items.COMMAND_BLOCK))
            .displayItems((parameters, output) -> {
                for (PrefabRegistrySyncPacket.VariantEntry entry : PrefabTabState.variantEntries()) {
                    ItemStack stack = buildPrefabStack(
                        entry.iconBlockId(), Items.COMMAND_BLOCK,
                        PrefabUseHandler.NBT_BV_PREFAB_ID, entry.id(), entry.committed());
                    output.accept(stack);
                }
            })
            .build()
    );

    public static final Supplier<CreativeModeTab> PREFAB_LOOT = register(
        "prefab_loot",
        () -> CreativeModeTab.builder()
            .title(Component.translatable("gui.dungeontrain.prefab_tab.loot"))
            .icon(() -> new ItemStack(Items.CHEST))
            .displayItems((parameters, output) -> {
                for (PrefabRegistrySyncPacket.LootEntry entry : PrefabTabState.lootEntries()) {
                    if (!LootPrefabStore.CATEGORY_LOOT.equals(entry.category())) continue;
                    ItemStack stack = buildPrefabStack(
                        entry.iconBlockId(), Items.CHEST,
                        PrefabUseHandler.NBT_LOOT_PREFAB_ID, entry.id(), entry.committed());
                    output.accept(stack);
                }
            })
            .build()
    );

    /**
     * Loot prefabs authored on armor stands or item frames. Combined into a
     * single tab so the two adjacent entity-loot flows stay near each other;
     * the underlying category field is still granular ({@code armor_stand}
     * vs {@code item_frame}) so a future split into two tabs is a one-line
     * filter change.
     */
    public static final Supplier<CreativeModeTab> PREFAB_LOOT_ENTITY = register(
        "prefab_loot_entity",
        () -> CreativeModeTab.builder()
            .title(Component.translatable("gui.dungeontrain.prefab_tab.loot_entity"))
            .icon(() -> new ItemStack(Items.ARMOR_STAND))
            .displayItems((parameters, output) -> {
                for (PrefabRegistrySyncPacket.LootEntry entry : PrefabTabState.lootEntries()) {
                    String cat = entry.category();
                    if (!LootPrefabStore.CATEGORY_ARMOR_STAND.equals(cat)
                        && !LootPrefabStore.CATEGORY_ITEM_FRAME.equals(cat)) continue;
                    Item fallback = LootPrefabStore.CATEGORY_ITEM_FRAME.equals(cat)
                        ? Items.ITEM_FRAME : Items.ARMOR_STAND;
                    ItemStack stack = buildPrefabStack(
                        entry.iconBlockId(), fallback,
                        PrefabUseHandler.NBT_LOOT_PREFAB_ID, entry.id(), entry.committed());
                    output.accept(stack);
                }
            })
            .build()
    );

    /**
     * Narrative content tab — collects every mod item and block that drives
     * in-world story delivery. Currently:
     * <ul>
     *   <li>{@link ModItems#RANDOM_BOOK} — placeholder for the chest-loot
     *       random-book intercept (substitutes a stamped vanilla written book
     *       at carriage-spawn time).</li>
     *   <li>{@link ModBlocks#NARRATIVE_LECTERN_ITEM} — progression-aware
     *       lectern variant (also remains in vanilla FUNCTIONAL_BLOCKS for
     *       discoverability).</li>
     * </ul>
     */
    public static final Supplier<CreativeModeTab> NARRATIVE = register(
        "narrative",
        () -> CreativeModeTab.builder()
            .title(Component.translatable("gui.dungeontrain.creative_tab.narrative"))
            .icon(() -> new ItemStack(Items.WRITTEN_BOOK))
            .displayItems((parameters, output) -> {
                output.accept(ModItems.RANDOM_BOOK.get());
                output.accept(ModBlocks.NARRATIVE_LECTERN_ITEM.get());
            })
            .build()
    );

    private ModCreativeTabs() {}

    private static Supplier<CreativeModeTab> register(String name, Supplier<CreativeModeTab> factory) {
        return DtRegistrar.get().register(Registries.CREATIVE_MODE_TAB, name, factory);
    }

    /** Call from the mod constructor to force this class's static fields (and their registrations) to run. */
    public static void init() {}

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
            // Item-only ids (armor_stand, item_frame, glow_item_frame, …) don't
            // resolve via the Block registry — fall through to the Item
            // registry so entity-category prefabs show with their item icon.
            if (item == fallbackItem) {
                Item itemOnly = BuiltInRegistries.ITEM.get(rl);
                if (itemOnly != Items.AIR) item = itemOnly;
            }
        }
        ItemStack stack = new ItemStack(item);
        CompoundTag tag = new CompoundTag();
        tag.putString(nbtKey, prefabId);
        if (!committed) tag.putBoolean(PrefabUseHandler.NBT_PREFAB_UNCOMMITTED, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }
}
