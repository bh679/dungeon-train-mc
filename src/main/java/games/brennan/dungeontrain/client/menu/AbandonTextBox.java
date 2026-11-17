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
 * One block of {@link AbandonConfirmScreen} text on a dark backing, so it reads over any world
 * (or the red wash), with an optional item icon on its left — the bed beside the save hint, the
 * Ender Chest beside the inventory line. Lines are wrapped once at build time and centred in the
 * space right of the icon.
 */
@OnlyIn(Dist.CLIENT)
final class AbandonTextBox {

    static final int LINE_H = 10;
    private static final int PAD = 4;
    private static final int ICON = 16;
    private static final int ICON_GAP = 4;
    private static final int BG = 0xC0101010;
    private static final int BORDER = 0x50FFFFFF;

    private record Line(FormattedCharSequence text, int color) {}

    private final List<Line> lines;
    private final ItemStack icon;
    private final int textW;
    private int x;
    private int y;

    private AbandonTextBox(List<Line> lines, ItemStack icon, int textW) {
        this.lines = lines;
        this.icon = icon;
        this.textW = textW;
    }

    /**
     * @param maxWidth the widest the whole box may be, padding and icon included
     * @param icon     drawn on the left, or {@link ItemStack#EMPTY} for none
     * @param parts    alternating {@link Component}, colour
     */
    static AbandonTextBox of(Font font, int maxWidth, ItemStack icon, Object... parts) {
        int wrap = Math.max(60, maxWidth - 2 * PAD - iconSpace(icon));
        List<Line> lines = new ArrayList<>();
        int widest = 0;
        for (int i = 0; i < parts.length; i += 2) {
            int color = (Integer) parts[i + 1];
            for (FormattedCharSequence s : font.split((Component) parts[i], wrap)) {
                lines.add(new Line(s, color));
                widest = Math.max(widest, font.width(s));
            }
        }
        return new AbandonTextBox(lines, icon, widest);
    }

    private static int iconSpace(ItemStack icon) {
        return icon.isEmpty() ? 0 : ICON + ICON_GAP;
    }

    int width() { return textW + 2 * PAD + iconSpace(icon); }

    int height() {
        int text = lines.size() * LINE_H - 1;   // the last line needs no leading below it
        return Math.max(text, icon.isEmpty() ? 0 : ICON) + 2 * PAD;
    }

    void place(int x, int y) {
        this.x = x;
        this.y = y;
    }

    void render(GuiGraphics g, Font font) {
        int w = width();
        int h = height();
        g.fill(x, y, x + w, y + h, BG);
        g.renderOutline(x, y, w, h, BORDER);
        if (!icon.isEmpty()) g.renderItem(icon, x + PAD, y + (h - ICON) / 2);
        int textLeft = x + PAD + iconSpace(icon);
        int lineY = y + (h - (lines.size() * LINE_H - 1)) / 2;
        for (Line l : lines) {
            g.drawString(font, l.text(), textLeft + (textW - font.width(l.text())) / 2, lineY, l.color());
            lineY += LINE_H;
        }
    }
}
