package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * One of the two choices on {@link AbandonConfirmScreen}: a dark card with a coloured border,
 * text blocks (each optionally led by an item icon), optionally the carried-items grid, and room
 * at the bottom for the card's button, which the screen places at {@link #buttonY()}. Two cards
 * given the same height put their buttons on one line.
 */
@OnlyIn(Dist.CLIENT)
final class AbandonCard {

    static final int PAD = 6;
    static final int GAP = 6;
    static final int BUTTON_H = 20;
    private static final int LINE_H = 10;
    private static final int ICON = 16;
    private static final int ICON_GAP = 4;
    private static final int BG = 0xC0101010;

    private record Block(ItemStack icon, List<FormattedCharSequence> lines, int color) {
        int height() {
            return Math.max(lines.size() * LINE_H - 1, icon.isEmpty() ? 0 : ICON);
        }
    }

    private final Font font;
    private final int width;
    private final int border;
    private final List<Block> blocks = new ArrayList<>();
    private AbandonInventoryGrid grid;
    private int x;
    private int y;
    private int height;

    AbandonCard(Font font, int width, int border) {
        this.font = font;
        this.width = width;
        this.border = border;
    }

    int innerWidth() { return width - 2 * PAD; }

    AbandonCard text(ItemStack icon, Component text, int color) {
        int wrap = Math.max(40, innerWidth() - (icon.isEmpty() ? 0 : ICON + ICON_GAP));
        blocks.add(new Block(icon, font.split(text, wrap), color));
        return this;
    }

    void grid(AbandonInventoryGrid grid) { this.grid = grid; }

    /** Height with no grid: padding, text, button. */
    int baseHeight() {
        int h = 2 * PAD + BUTTON_H + GAP;
        for (int i = 0; i < blocks.size(); i++) h += blocks.get(i).height() + (i > 0 ? GAP : 0);
        return h;
    }

    int naturalHeight() {
        return baseHeight() + (grid == null ? 0 : GAP + grid.height());
    }

    int width() { return width; }

    void place(int x, int y, int height) {
        this.x = x;
        this.y = y;
        this.height = height;
        if (grid != null) grid.place(x + (width - grid.width()) / 2, y + baseHeight() - PAD - BUTTON_H);
    }

    int x() { return x; }

    int buttonY() { return y + height - PAD - BUTTON_H; }

    void render(GuiGraphics g, float fade) {
        g.fill(x, y, x + width, y + height, BG);
        g.renderOutline(x, y, width, height, border);
        int by = y + PAD;
        for (Block b : blocks) {
            int textX = x + PAD;
            if (!b.icon().isEmpty()) {
                g.renderItem(b.icon(), x + PAD, by + (b.height() - ICON) / 2);
                textX += ICON + ICON_GAP;
            }
            int ly = by + (b.height() - (b.lines().size() * LINE_H - 1)) / 2;
            for (FormattedCharSequence line : b.lines()) {
                g.drawString(font, line, textX, ly, b.color());
                ly += LINE_H;
            }
            by += b.height() + GAP;
        }
        if (grid != null) grid.render(g, font, fade);
    }

    void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (grid != null) grid.renderTooltip(g, font, mouseX, mouseY);
    }
}
