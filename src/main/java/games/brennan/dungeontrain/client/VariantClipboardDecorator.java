package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.item.VariantClipboardIconLayout;
import games.brennan.dungeontrain.item.VariantClipboardItem;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a copied block variant's contents onto its slot icon, so clipboards tell themselves apart
 * without a hover: the cell's group number (lock-id, the same number as the in-world badge) in the
 * top-left corner, and every variant as a tiny icon in a grid along the bottom —
 * {@link VariantClipboardIconLayout} decides where each one goes.
 *
 * <p>Decoding a clipboard parses every block-state string, which is far too slow to do per slot
 * per frame, so the result is memoised by the stack's {@link CustomData} (immutable, compared by
 * value) in a small LRU.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class VariantClipboardDecorator {

    /** Above the base item model (vanilla decorations draw their text at this depth too). */
    private static final float OVERLAY_Z = 200.0f;
    /** Vanilla's yellow chat colour — reads against the pale ghost cube. */
    private static final int NUMBER_COLOR = 0xFFFF55;
    private static final int CACHE_SIZE = 64;

    private static final Map<CustomData, Contents> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CustomData, Contents> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    /** What a clipboard's icon shows: its lock-id (0 = none) and one icon per drawable variant. */
    private record Contents(int lockId, List<ItemStack> icons) {
        static final Contents EMPTY = new Contents(0, List.of());

        boolean isEmpty() {
            return lockId <= 0 && icons.isEmpty();
        }
    }

    private VariantClipboardDecorator() {}

    @SubscribeEvent
    public static void registerDecorations(RegisterItemDecorationsEvent event) {
        event.register(ModItems.VARIANT_CLIPBOARD.get(), VariantClipboardDecorator::render);
    }

    private static boolean render(GuiGraphics graphics, Font font, ItemStack stack, int x, int y) {
        Contents contents = contentsOf(stack);
        if (contents.isEmpty()) return false;
        renderGrid(graphics, contents.icons(), x, y);
        if (contents.lockId() > 0) renderNumber(graphics, font, contents.lockId(), x, y);
        return true;
    }

    private static void renderGrid(GuiGraphics graphics, List<ItemStack> icons, int x, int y) {
        float scale = (float) VariantClipboardIconLayout.CELL / VariantClipboardIconLayout.ICON;
        for (int i = 0; i < icons.size(); i++) {
            int[] cell = VariantClipboardIconLayout.cellOrigin(i);
            if (cell == null) break;
            graphics.pose().pushPose();
            graphics.pose().translate(x + cell[0], y + cell[1], OVERLAY_Z);
            graphics.pose().scale(scale, scale, scale);
            graphics.renderItem(icons.get(i), 0, 0);
            graphics.pose().popPose();
        }
    }

    /** The group number, top-left; three digits and up drop to half size so they still fit. */
    private static void renderNumber(GuiGraphics graphics, Font font, int lockId, int x, int y) {
        String label = Integer.toString(lockId);
        float scale = label.length() >= 3 ? 0.5f : 1.0f;
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, OVERLAY_Z + 100.0f);
        graphics.pose().scale(scale, scale, 1.0f);
        graphics.drawString(font, label, 0, 0, NUMBER_COLOR, true);
        graphics.pose().popPose();
    }

    private static Contents contentsOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) return Contents.EMPTY;
        return CACHE.computeIfAbsent(data, VariantClipboardDecorator::decode);
    }

    private static Contents decode(CustomData data) {
        CompoundTag tag = data.copyTag();
        List<VariantState> states = VariantClipboardItem.decodeStates(tag);
        int cap = Math.min(states.size(), VariantClipboardIconLayout.MAX_CELLS);
        List<ItemStack> icons = new ArrayList<>(cap);
        for (VariantState state : states) {
            if (icons.size() >= cap) break;
            ItemStack icon = iconOf(state);
            if (!icon.isEmpty()) icons.add(icon);
        }
        return new Contents(VariantClipboardItem.decodeLockId(tag), List.copyOf(icons));
    }

    /** A block's item, a mob's spawn egg; empty for the empty-cell sentinel and itemless blocks. */
    private static ItemStack iconOf(VariantState state) {
        if (state.isMob()) {
            SpawnEggItem egg = BuiltInRegistries.ENTITY_TYPE.getOptional(state.entityId())
                .map(SpawnEggItem::byId)
                .orElse(null);
            return egg == null ? ItemStack.EMPTY : new ItemStack(egg);
        }
        if (CarriageVariantBlocks.isEmptyPlaceholder(state.state())) return ItemStack.EMPTY;
        Item item = state.state().getBlock().asItem();
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }
}
