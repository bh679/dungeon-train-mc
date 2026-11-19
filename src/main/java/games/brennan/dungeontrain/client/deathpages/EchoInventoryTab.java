package games.brennan.dungeontrain.client.deathpages;

import games.brennan.dungeontrain.client.live.deathpages.LiveDeathChrome;
import games.brennan.dungeontrain.echo.EchoSummary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

import static games.brennan.dungeontrain.client.deathpages.EchoPaint.ARMOR_BAR_W;
import static games.brennan.dungeontrain.client.deathpages.EchoPaint.ARMOR_ICON;
import static games.brennan.dungeontrain.client.deathpages.EchoPaint.GAP;
import static games.brennan.dungeontrain.client.deathpages.EchoPaint.NAME;
import static games.brennan.dungeontrain.client.deathpages.EchoPaint.SLOT;
import static games.brennan.dungeontrain.client.deathpages.EchoPaint.SLOT_LIGHT;

/**
 * The echo page's Inventory tab, laid out like the vanilla inventory: the four armour slots with the
 * offhand under them; the HUD armour bar over a 3×3 grid (the main hand, then the backpack); and in
 * the room to the right, a details panel for one item: a large icon, its name and its own tooltip
 * lines. The hovered slot shows there; otherwise the last one clicked, starting on the main hand.
 */
final class EchoInventoryTab {

    /** Width this tab needs: armour column, gap, armour bar (wider than the grid), gap, a details panel. */
    static final int MIN_W = SLOT + 8 + ARMOR_BAR_W + 8 + 96;

    private static final int OFFHAND_GAP = 8;
    private static final int MAIN_HAND = 5;
    private static final int DIVIDER = SLOT_LIGHT;

    private record SlotHit(int index, int x, int y, ItemStack stack) {
        boolean has(double mx, double my) {
            return mx >= x && mx < x + SLOT && my >= y && my < y + SLOT;
        }
    }

    private final LiveDeathChrome chrome;
    private final List<SlotHit> slotHits = new ArrayList<>();
    /** The clicked slot (armour 0–3, offhand 4, main hand 5, backpack 6–13). */
    private int selected = MAIN_HAND;
    private int armourX, armourY;
    private int armourPoints;

    EchoInventoryTab(LiveDeathChrome chrome) {
        this.chrome = chrome;
    }

    /** Draw the tab into the box at ({@code x}, {@code y}), {@code w} × {@code h}. */
    void draw(GuiGraphics g, EchoSummary echo, int x, int y, int w, int h, int mouseX, int mouseY,
              boolean settled) {
        slotHits.clear();
        ItemStack[] stacks = stacks(echo);

        // Armour column with the offhand under it.
        for (int i = 0; i < 4; i++) {
            slot(g, i, x, y + i * (SLOT + GAP), stacks[i], settled);
        }
        slot(g, 4, x, y + 4 * SLOT + 3 * GAP + OFFHAND_GAP, stacks[4], settled);

        // Armour bar over the 3×3 grid: main hand top-left, then the backpack.
        int gx = x + SLOT + 8;
        armourPoints = echo.armour();
        armourX = gx;
        armourY = y;
        if (settled) {
            EchoPaint.armourBar(g, gx, y, armourPoints);
        }
        int gy = y + ARMOR_ICON + 3;
        for (int i = 0; i < 9; i++) {
            slot(g, MAIN_HAND + i, gx + (i % 3) * (SLOT + GAP), gy + (i / 3) * (SLOT + GAP),
                    stacks[MAIN_HAND + i], settled);
        }

        // Details for the hovered slot, else the selected one.
        int dx = gx + ARMOR_BAR_W + 8;
        g.fill(dx - 4, y, dx - 3, y + h, chrome.fade(DIVIDER));
        int shown = selected;
        for (SlotHit hit : slotHits) {
            if (hit.has(mouseX, mouseY) && !hit.stack().isEmpty()) {
                shown = hit.index();
            }
        }
        details(g, stacks[shown], dx, y, x + w - dx, h, settled);
    }

    /** Every slot in index order: armour 0–3 (head first), offhand 4, main hand 5, backpack 6–13. */
    private static ItemStack[] stacks(EchoSummary echo) {
        ItemStack[] s = new ItemStack[MAIN_HAND + 1 + EchoSummary.MAX_CARRIED];
        s[0] = echo.appearance().head();
        s[1] = echo.appearance().chest();
        s[2] = echo.appearance().legs();
        s[3] = echo.appearance().feet();
        s[4] = echo.appearance().offHand();
        s[MAIN_HAND] = echo.appearance().mainHand();
        for (int i = 0; i < EchoSummary.MAX_CARRIED; i++) {
            s[MAIN_HAND + 1 + i] = i < echo.carried().size() ? echo.carried().get(i) : ItemStack.EMPTY;
        }
        return s;
    }

    private void slot(GuiGraphics g, int index, int x, int y, ItemStack stack, boolean settled) {
        EchoPaint.slotFrame(g, chrome, x, y, index == selected);
        if (!stack.isEmpty() && settled) {
            g.renderItem(stack, x + 1, y + 1);
            g.renderItemDecorations(chrome.font(), stack, x + 1, y + 1);
        }
        slotHits.add(new SlotHit(index, x, y, stack));
    }

    /** A large icon and the item's own tooltip lines, wrapped to {@code w} and cut at {@code h}. */
    private void details(GuiGraphics g, ItemStack stack, int x, int y, int w, int h, boolean settled) {
        if (stack.isEmpty() || w < 40) return;
        Font font = chrome.font();
        if (settled) {
            g.pose().pushPose();
            g.pose().translate(x, y, 0.0F);
            g.pose().scale(2.0F, 2.0F, 1.0F);
            g.renderItem(stack, 0, 0);
            g.pose().popPose();
        }
        List<Component> lines = Screen.getTooltipFromItem(Minecraft.getInstance(), stack);
        int ty = y;
        int nameX = x + 36;
        // The name beside the icon, wrapped to the room left of it.
        if (!lines.isEmpty()) {
            for (FormattedCharSequence part : font.split(lines.get(0), x + w - nameX)) {
                g.drawString(font, part, nameX, ty, chrome.fade(NAME), true);
                ty += 10;
            }
        }
        ty = Math.max(ty, y + 36);
        for (int i = 1; i < lines.size(); i++) {
            for (FormattedCharSequence part : font.split(lines.get(i), w)) {
                if (ty + 9 > y + h) return;
                g.drawString(font, part, x, ty, chrome.fade(0xFFAAAAAA), true);
                ty += 10;
            }
        }
    }

    /** The armour bar's number on hover. */
    boolean tooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (mouseX >= armourX && mouseX < armourX + ARMOR_BAR_W && mouseY >= armourY && mouseY < armourY + ARMOR_ICON) {
            g.renderTooltip(chrome.font(), Component.translatable("gui.dungeontrain.death.echo.armour_tip",
                    armourPoints), mouseX, mouseY);
            return true;
        }
        return false;
    }

    /** Select a clicked slot with an item in it. */
    boolean mouseClicked(double mx, double my) {
        for (SlotHit hit : slotHits) {
            if (hit.has(mx, my) && !hit.stack().isEmpty()) {
                selected = hit.index();
                return true;
            }
        }
        return false;
    }
}
