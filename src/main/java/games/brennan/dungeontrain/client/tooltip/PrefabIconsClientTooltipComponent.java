package games.brennan.dungeontrain.client.tooltip;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Renders the prefab's constituent blocks/items as a 9-wide grid of 16-px
 * icons inside the hover tooltip. Mirrors the layout of vanilla bundle
 * tooltips (which render bundle contents the same way) so it composes
 * naturally with vanilla tooltip stacking.
 *
 * <p>Layout: {@link #ICONS_PER_ROW} icons per row, max {@link #MAX_ROWS}
 * rows (= {@link #MAX_ICONS} total). Each cell is {@link #CELL_SIZE} px so
 * vanilla item-icon padding renders cleanly. If the source prefab has more
 * entries than fit, "+N more" text renders below the grid.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PrefabIconsClientTooltipComponent implements ClientTooltipComponent {

    public static final int ICONS_PER_ROW = 9;
    public static final int MAX_ROWS = 4;
    public static final int MAX_ICONS = ICONS_PER_ROW * MAX_ROWS;
    public static final int CELL_SIZE = 18;

    private final List<ItemStack> stacks;
    private final int hiddenCount;

    public PrefabIconsClientTooltipComponent(PrefabIconsTooltipData data) {
        this.stacks = data.stacks();
        this.hiddenCount = data.hiddenCount();
    }

    private int rows() {
        return stacks.isEmpty() ? 0 : ((stacks.size() - 1) / ICONS_PER_ROW) + 1;
    }

    @Override
    public int getHeight() {
        int gridHeight = rows() * CELL_SIZE;
        return gridHeight + (hiddenCount > 0 ? 10 : 0);
    }

    @Override
    public int getWidth(Font font) {
        int columns = Math.min(stacks.size(), ICONS_PER_ROW);
        int gridWidth = columns * CELL_SIZE;
        if (hiddenCount > 0) {
            int textWidth = font.width(overflowText());
            return Math.max(gridWidth, textWidth);
        }
        return gridWidth;
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics g) {
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty()) continue;
            int col = i % ICONS_PER_ROW;
            int row = i / ICONS_PER_ROW;
            int px = x + col * CELL_SIZE;
            int py = y + row * CELL_SIZE;
            g.renderItem(stack, px, py);
            g.renderItemDecorations(font, stack, px, py);
        }
    }

    @Override
    public void renderText(Font font, int x, int y, org.joml.Matrix4f matrix, net.minecraft.client.renderer.MultiBufferSource.BufferSource buffer) {
        if (hiddenCount <= 0) return;
        int textY = y + rows() * CELL_SIZE;
        font.drawInBatch(
            overflowText(),
            (float) x,
            (float) textY,
            0xAAAAAA,
            false,
            matrix,
            buffer,
            Font.DisplayMode.NORMAL,
            0,
            15728880
        );
    }

    private Component overflowText() {
        return Component.literal("+" + hiddenCount + " more");
    }
}
