package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Renders the prefab grid panel that overpaints the vanilla items area
 * when one of the left-side prefab tabs is active. Pure rendering helper —
 * all state lives in {@link PrefabTabState}.
 *
 * <p>Layout matches vanilla's 9×5 item slots so the panel slots into the
 * existing creative inventory's content rect. Each slot is 18×18; the panel
 * origin is at {@code (leftPos+9, topPos+18)}, the same offset vanilla uses
 * for its items grid.</p>
 *
 * <p>Click semantics: a left-click on a populated slot puts the prefab item
 * stack into the player's currently-selected hotbar slot via
 * {@code handleCreativeModeItemAdd} — the same path vanilla's middle-click
 * pick-block uses, so the stack survives inventory close.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PrefabTabPanel {

    public static final int COLS = 9;
    public static final int ROWS = 5;
    public static final int SLOT_SIZE = 18;
    public static final int GRID_OFFSET_X = 9;
    public static final int GRID_OFFSET_Y = 18;

    private static final int BACKGROUND = 0xFFC6C6C6;
    private static final int SLOT_INNER = 0xFF8B8B8B;
    private static final int HEADER = 0xFF373737;

    private PrefabTabPanel() {}

    /**
     * Paint the panel — call from inside the mixin's render TAIL inject when
     * {@link PrefabTabState#activeTab()} is non-NONE.
     */
    public static void render(
        GuiGraphics g, Font font, int leftPos, int topPos, int mouseX, int mouseY
    ) {
        PrefabTabState.Tab tab = PrefabTabState.activeTab();
        if (tab == PrefabTabState.Tab.NONE) return;

        int gridX = leftPos + GRID_OFFSET_X;
        int gridY = topPos + GRID_OFFSET_Y;
        int gridW = COLS * SLOT_SIZE;
        int gridH = ROWS * SLOT_SIZE;

        // Opaque background — hides vanilla's items + any vanilla tooltip
        // that was painted under this z-level.
        g.fill(gridX - 1, gridY - 1, gridX + gridW + 1, gridY + gridH + 1, BACKGROUND);

        List<String> ids = PrefabTabState.activeIds();
        int scroll = PrefabTabState.scrollOffset();
        int firstIndex = scroll * COLS;

        // Header text — small label at the top of the panel.
        Component header = headerLabel(tab);
        g.drawString(font, header, gridX + 2, gridY - 10, 0xFFFFFFFF, true);

        // Empty-state hint.
        if (ids.isEmpty()) {
            g.drawString(font, Component.translatable("gui.dungeontrain.prefab_tab.empty"),
                gridX + 4, gridY + 4, HEADER, false);
            return;
        }

        // Slot frames + items.
        ItemStack hovered = ItemStack.EMPTY;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int idx = firstIndex + row * COLS + col;
                if (idx >= ids.size()) continue;

                int slotX = gridX + col * SLOT_SIZE;
                int slotY = gridY + row * SLOT_SIZE;

                g.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, SLOT_INNER);

                ItemStack stack = PrefabTabState.stackFor(tab, idx);
                g.renderItem(stack, slotX + 1, slotY + 1);

                if (mouseX >= slotX && mouseX < slotX + SLOT_SIZE
                    && mouseY >= slotY && mouseY < slotY + SLOT_SIZE) {
                    hovered = stack;
                }
            }
        }

        // Render hovered tooltip on top — the prefab id appears as the
        // stack's displayName via VariantPrefabItem.getName / ContentsPrefabItem.getName.
        if (!hovered.isEmpty()) {
            g.renderTooltip(font, hovered, mouseX, mouseY);
        }
    }

    /**
     * Process a click inside the panel. Returns true when the click was
     * consumed so the mixin can cancel vanilla's slot-click handling.
     */
    public static boolean handleClick(int leftPos, int topPos, double mouseX, double mouseY, int button) {
        PrefabTabState.Tab tab = PrefabTabState.activeTab();
        if (tab == PrefabTabState.Tab.NONE) return false;

        int gridX = leftPos + GRID_OFFSET_X;
        int gridY = topPos + GRID_OFFSET_Y;
        int gridW = COLS * SLOT_SIZE;
        int gridH = ROWS * SLOT_SIZE;

        if (mouseX < gridX || mouseY < gridY || mouseX >= gridX + gridW || mouseY >= gridY + gridH) {
            // Outside our panel — let vanilla / side tab buttons handle it.
            return false;
        }

        if (button != 0) {
            // Right/middle-click inside our panel — consume to prevent vanilla
            // slot interaction but no-op (could host context-menu later).
            return true;
        }

        int col = (int) ((mouseX - gridX) / SLOT_SIZE);
        int row = (int) ((mouseY - gridY) / SLOT_SIZE);
        int idx = PrefabTabState.scrollOffset() * COLS + row * COLS + col;

        ItemStack stack = PrefabTabState.stackFor(tab, idx);
        if (stack.isEmpty()) return true;

        putInSelectedHotbar(stack);
        return true;
    }

    /** Scroll the panel content. Returns true if the scroll was inside our area. */
    public static boolean handleScroll(int leftPos, int topPos, double mouseX, double mouseY, double delta) {
        if (PrefabTabState.activeTab() == PrefabTabState.Tab.NONE) return false;
        int gridX = leftPos + GRID_OFFSET_X;
        int gridY = topPos + GRID_OFFSET_Y;
        int gridW = COLS * SLOT_SIZE;
        int gridH = ROWS * SLOT_SIZE;
        if (mouseX < gridX || mouseY < gridY || mouseX >= gridX + gridW || mouseY >= gridY + gridH) {
            return false;
        }
        int total = PrefabTabState.activeIds().size();
        int rowsTotal = (total + COLS - 1) / COLS;
        int maxScroll = Math.max(0, rowsTotal - ROWS);
        PrefabTabState.scrollBy(delta < 0 ? 1 : -1, maxScroll);
        return true;
    }

    /**
     * Push the prefab stack into the player's currently-selected hotbar
     * slot. Mirrors vanilla's {@code Minecraft.pickBlock} sync path so the
     * server agrees the stack is now in that slot — survives inventory
     * close, can be right-clicked in world straight away.
     */
    private static void putInSelectedHotbar(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) return;
        Inventory inv = player.getInventory();
        int slot = inv.selected; // 0..8 for hotbar
        inv.setItem(slot, stack.copy());
        mc.gameMode.handleCreativeModeItemAdd(stack.copy(), 36 + slot);
    }

    private static Component headerLabel(PrefabTabState.Tab tab) {
        return switch (tab) {
            case VARIANTS -> Component.translatable("gui.dungeontrain.prefab_tab.variants");
            case LOOT -> Component.translatable("gui.dungeontrain.prefab_tab.loot");
            case NONE -> Component.empty();
        };
    }
}
