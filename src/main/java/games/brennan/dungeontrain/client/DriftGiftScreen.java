package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.perks.effects.builder.DriftGiftMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import java.util.List;

/**
 * Vanilla's chest window for a {@link DriftGiftMenu}, plus a small panel to its right holding the
 * gift slot. The panel chrome follows {@link FreePlayEnderChestScreen}'s wings (vanilla's inventory
 * palette). Hovering the empty gift slot explains what it does and the payout rate.
 */
public final class DriftGiftScreen extends AbstractContainerScreen<DriftGiftMenu> {

    private static final ResourceLocation TEXTURE =
        ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");

    private static final int HEADER_HEIGHT = 17;
    private static final int ROW_HEIGHT = 18;
    private static final int TEXTURE_FOOTER_V = HEADER_HEIGHT + 6 * ROW_HEIGHT + 1;
    private static final int FOOTER_HEIGHT = 96;

    private static final int FACE = 0xFFC6C6C6;
    private static final int OUTLINE = 0xFF000000;
    private static final int LIGHT = 0xFFFFFFFF;
    private static final int SHADE = 0xFF555555;
    private static final int SLOT_BG = 0xFF8B8B8B;
    private static final int CORNER_CUT = 2;

    /** Panel around the gift slot, relative to the window's top-left. */
    private static final int PANEL_X0 = DriftGiftMenu.WINDOW_WIDTH + 2;
    private static final int PANEL_Y0 = DriftGiftMenu.GIFT_SLOT_Y - 7;
    private static final int PANEL_X1 = DriftGiftMenu.GIFT_SLOT_X + 16 + 5;
    private static final int PANEL_Y1 = DriftGiftMenu.GIFT_SLOT_Y + 16 + 7;

    public DriftGiftScreen(DriftGiftMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageHeight = HEADER_HEIGHT + menu.rows() * ROW_HEIGHT + FOOTER_HEIGHT + 1;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        Slot hovered = this.hoveredSlot;
        if (hovered != null && hovered.index == menu.giftSlotIndex() && !hovered.hasItem()) {
            graphics.renderTooltip(font, List.of(
                    Component.translatable(DriftGiftMenu.KEY_SLOT_TOOLTIP, menu.ratePercent()).getVisualOrderText()),
                mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        int rows = menu.rows();
        graphics.blit(TEXTURE, x, y, 0, 0, imageWidth, HEADER_HEIGHT);
        for (int row = 0; row < rows; row++) {
            graphics.blit(TEXTURE, x, y + HEADER_HEIGHT + row * ROW_HEIGHT, 0, HEADER_HEIGHT, imageWidth, ROW_HEIGHT);
        }
        graphics.blit(TEXTURE, x, y + HEADER_HEIGHT + rows * ROW_HEIGHT, 0, TEXTURE_FOOTER_V, imageWidth, FOOTER_HEIGHT);
        drawPanel(graphics);
    }

    /** A click on the panel is a click on the GUI, not outside it. */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int guiLeft, int guiTop, int button) {
        if (mouseX >= leftPos + PANEL_X0 && mouseX < leftPos + PANEL_X1
                && mouseY >= topPos + PANEL_Y0 && mouseY < topPos + PANEL_Y1) {
            return false;
        }
        return super.hasClickedOutside(mouseX, mouseY, guiLeft, guiTop, button);
    }

    private void drawPanel(GuiGraphics g) {
        int x0 = leftPos + PANEL_X0;
        int y0 = topPos + PANEL_Y0;
        int x1 = leftPos + PANEL_X1;
        int y1 = topPos + PANEL_Y1;
        fillRounded(g, x0, y0, x1, y1, OUTLINE, CORNER_CUT);
        drawBevel(g, x0 + 1, y0 + 1, x1 - 1, y1 - 1, CORNER_CUT - 1);
        g.fill(x0 + 2, y0 + 2, x1 - 2, y1 - 2, FACE);
        int sx = leftPos + DriftGiftMenu.GIFT_SLOT_X;
        int sy = topPos + DriftGiftMenu.GIFT_SLOT_Y;
        g.fill(sx - 1, sy - 1, sx + 17, sy + 17, SHADE);
        g.fill(sx, sy, sx + 18, sy + 18, LIGHT);
        g.fill(sx, sy, sx + 16, sy + 16, SLOT_BG);
    }

    private static void fillRounded(GuiGraphics g, int x0, int y0, int x1, int y1, int color, int cut) {
        for (int d = 0; d < cut; d++) {
            int inset = cut - d;
            g.fill(x0 + inset, y0 + d, x1 - inset, y0 + d + 1, color);
            g.fill(x0 + inset, y1 - d - 1, x1 - inset, y1 - d, color);
        }
        int m = Math.max(cut, 0);
        g.fill(x0, y0 + m, x1, y1 - m, color);
    }

    private static void drawBevel(GuiGraphics g, int x0, int y0, int x1, int y1, int cut) {
        fillRounded(g, x0, y0, x1, y1, LIGHT, cut);
        if (y1 - 1 > y0 + 1) g.fill(x1 - 1, y0 + 1, x1, y1 - 1, SHADE);
        int from = x0 + 1 + cut;
        int to = x1 - cut;
        if (to > from) g.fill(from, y1 - 1, to, y1, SHADE);
    }
}
