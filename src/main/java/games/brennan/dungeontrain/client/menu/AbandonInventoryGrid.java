package games.brennan.dungeontrain.client.menu;

import games.brennan.ediblebackpacks.menu.BackpackLayout;
import games.brennan.ediblebackpacks.registry.ModAttachments;
import games.brennan.ediblebackpacks.storage.BackpackData;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the player is carrying, drawn read-only for {@link AbandonConfirmScreen}: armour down
 * the left (head to feet) with the offhand under it, the 27 main slots, the hotbar below them, and
 * any unlocked Edible Backpack slots in grey side panels laid out exactly as the inventory screen
 * lays them out ({@link BackpackLayout}: 9-tall columns, the left panel filling outward first,
 * slot 54 onwards on the right).
 *
 * <p>Positions are fixed when the grid is built; the stacks are read live every frame, so the
 * grid never shows a stale copy.</p>
 */
@OnlyIn(Dist.CLIENT)
final class AbandonInventoryGrid {

    static final int SLOT = BackpackLayout.SLOT;
    private static final int ARMOR_GAP = 4;
    private static final int HOTBAR_GAP = 4;
    private static final int PANEL_GAP = BackpackLayout.GAP;
    private static final int BORDER = BackpackLayout.BORDER;

    /** The player's own grid: armour column + 9 wide. */
    private static final int CORE_W = SLOT + ARMOR_GAP + 9 * SLOT;
    /** Armour (4) + gap + offhand. Taller than main (3) + gap + hotbar. */
    private static final int CORE_H = 4 * SLOT + HOTBAR_GAP + SLOT;

    private static final int SLOT_BG = 0xFF8B8B8B;
    private static final int SLOT_DARK = 0xFF373737;
    private static final int SLOT_LIGHT = 0xFFFFFFFF;
    private static final int PANEL_FACE = 0xFFC6C6C6;
    private static final int PANEL_SHADE = 0xFF555555;

    /** {@code index} is an {@link Inventory} slot, or a backpack slot when {@code backpack}. */
    private record Cell(int x, int y, boolean backpack, int index) {}

    private record Panel(int x, int y, int w, int h) {}

    private final Player player;
    private final int unlocked;
    private final List<Cell> cells = new ArrayList<>();
    private final List<Panel> panels = new ArrayList<>();
    private final int leftW;
    private final int rightW;
    private final int height;

    private AbandonInventoryGrid(Player player, int unlocked) {
        this.player = player;
        this.unlocked = unlocked;
        int left = BackpackLayout.unlockedOnPanel(unlocked, false);
        int right = BackpackLayout.unlockedOnPanel(unlocked, true);
        this.leftW = panelWidth(left);
        this.rightW = panelWidth(right);
        this.height = Math.max(CORE_H, Math.max(panelHeight(left), panelHeight(right)));
    }

    /**
     * @param withBackpack false leaves the backpack out (used when the screen is too short for it)
     * @return null with no player to read from
     */
    static AbandonInventoryGrid of(Player player, boolean withBackpack) {
        if (player == null) return null;
        return new AbandonInventoryGrid(player, withBackpack ? unlockedSlots(player) : 0);
    }

    int width() { return leftW + CORE_W + rightW; }

    int height() { return height; }

    /** Fix every slot's screen position, the grid's top-left at ({@code x}, {@code y}). */
    void place(int x, int y) {
        cells.clear();
        panels.clear();
        int coreX = x + leftW;
        int coreY = y + (height - CORE_H) / 2;
        placeCore(coreX, coreY);
        placeBackpack(coreX, y);
    }

    private void placeCore(int x, int y) {
        for (int i = 0; i < 4; i++) {
            cells.add(new Cell(x, y + i * SLOT, false, Inventory.INVENTORY_SIZE + 3 - i)); // head first
        }
        cells.add(new Cell(x, y + 4 * SLOT + HOTBAR_GAP, false, Inventory.SLOT_OFFHAND));
        int mainX = x + SLOT + ARMOR_GAP;
        for (int i = 9; i < Inventory.INVENTORY_SIZE; i++) {
            int row = (i - 9) / 9;
            cells.add(new Cell(mainX + (i % 9) * SLOT, y + row * SLOT, false, i));
        }
        int hotY = y + 3 * SLOT + HOTBAR_GAP;
        for (int i = 0; i < 9; i++) cells.add(new Cell(mainX + i * SLOT, hotY, false, i));
    }

    private void placeBackpack(int coreX, int top) {
        if (unlocked <= 0) return;
        int left = BackpackLayout.unlockedOnPanel(unlocked, false);
        int right = BackpackLayout.unlockedOnPanel(unlocked, true);
        int coreRight = coreX + CORE_W;
        int leftTop = top + (height - panelHeight(left)) / 2;
        int rightTop = top + (height - panelHeight(right)) / 2;
        if (left > 0) {
            int w = panelWidth(left) - PANEL_GAP;
            panels.add(new Panel(coreX - PANEL_GAP - w, leftTop, w, panelHeight(left)));
        }
        if (right > 0) {
            panels.add(new Panel(coreRight + PANEL_GAP, rightTop, panelWidth(right) - PANEL_GAP, panelHeight(right)));
        }
        for (int i = 0; i < unlocked; i++) {
            int col = BackpackLayout.columnOf(i);
            int row = BackpackLayout.rowOf(i);
            boolean onRight = BackpackLayout.isRightPanel(i);
            int x = onRight
                    ? coreRight + PANEL_GAP + BORDER + col * SLOT
                    : coreX - PANEL_GAP - BORDER - SLOT - col * SLOT;
            int y = (onRight ? rightTop : leftTop) + BORDER + row * SLOT;
            cells.add(new Cell(x, y, true, i));
        }
    }

    void render(GuiGraphics g, Font font) {
        for (Panel p : panels) drawPanel(g, p);
        for (Cell c : cells) {
            drawSlot(g, c.x(), c.y());
            ItemStack stack = stackAt(c);
            if (!stack.isEmpty()) {
                g.renderItem(stack, c.x() + 1, c.y() + 1);
                g.renderItemDecorations(font, stack, c.x() + 1, c.y() + 1);
            }
        }
    }

    /** The vanilla item tooltip for the slot under the mouse, if it holds anything. */
    void renderTooltip(GuiGraphics g, Font font, int mouseX, int mouseY) {
        for (Cell c : cells) {
            if (mouseX < c.x() || mouseY < c.y() || mouseX >= c.x() + SLOT || mouseY >= c.y() + SLOT) continue;
            ItemStack stack = stackAt(c);
            if (!stack.isEmpty()) g.renderTooltip(font, stack, mouseX, mouseY);
            return;
        }
    }

    private ItemStack stackAt(Cell c) {
        if (!c.backpack()) return player.getInventory().getItem(c.index());
        ItemStackHandler items = backpack(player).items();
        return c.index() < items.getSlots() ? items.getStackInSlot(c.index()) : ItemStack.EMPTY;
    }

    private static BackpackData backpack(Player player) {
        return player.getData(ModAttachments.BACKPACK);
    }

    private static int unlockedSlots(Player player) {
        BackpackData data = backpack(player);
        return Math.max(0, Math.min(data.unlocked(), data.items().getSlots()));
    }

    /** Panel plus the gap that separates it from the player's grid; 0 for an empty panel. */
    private static int panelWidth(int slots) {
        return slots <= 0 ? 0 : PANEL_GAP + BackpackLayout.columnsFor(slots) * SLOT + 2 * BORDER;
    }

    private static int panelHeight(int slots) {
        return slots <= 0 ? 0 : Math.min(BackpackLayout.ROWS, slots) * SLOT + 2 * BORDER;
    }

    private static void drawPanel(GuiGraphics g, Panel p) {
        g.fill(p.x(), p.y(), p.x() + p.w(), p.y() + p.h(), 0xFF000000);
        g.fill(p.x() + 1, p.y() + 1, p.x() + p.w() - 1, p.y() + p.h() - 1, PANEL_FACE);
        g.fill(p.x() + 1, p.y() + 1, p.x() + p.w() - 2, p.y() + 2, SLOT_LIGHT);
        g.fill(p.x() + 1, p.y() + 1, p.x() + 2, p.y() + p.h() - 2, SLOT_LIGHT);
        g.fill(p.x() + 2, p.y() + p.h() - 2, p.x() + p.w() - 1, p.y() + p.h() - 1, PANEL_SHADE);
        g.fill(p.x() + p.w() - 2, p.y() + 2, p.x() + p.w() - 1, p.y() + p.h() - 1, PANEL_SHADE);
    }

    private static void drawSlot(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + SLOT, y + SLOT, SLOT_BG);
        g.fill(x, y, x + SLOT, y + 1, SLOT_DARK);
        g.fill(x, y, x + 1, y + SLOT, SLOT_DARK);
        g.fill(x, y + SLOT - 1, x + SLOT, y + SLOT, SLOT_LIGHT);
        g.fill(x + SLOT - 1, y, x + SLOT, y + SLOT, SLOT_LIGHT);
    }
}
