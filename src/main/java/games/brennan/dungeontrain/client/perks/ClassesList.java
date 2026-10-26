package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkTree;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Classes list down the right edge (the web page's {@code renderLockedList}): every class in circle
 * order, in its colour when shown and muted when hidden. Hovering a hidden class previews it on the
 * sky; hovering any class makes its perks glow. A click on a hidden class shows it; on a shown one it
 * selects it (the bar keeps showing it). Shown classes get an eye with a slash to their right that
 * hides them; every row leaves room for it so the names never shift.
 */
@OnlyIn(Dist.CLIENT)
final class ClassesList {

    private static final int RIGHT = 8, TOP_GAP = 8, ROW = 11, HEAD = 12;
    private static final float HEAD_SCALE = 0.75F;
    private static final double DASH = 12;
    /** The hide icon's column right of the names. */
    private static final int EYE_W = 7, EYE_H = 5, EYE_GAP = 5;

    private final List<PerkTree.PerkClass> order;
    private final Font font;
    private int top;
    private int left;
    private int screenW;

    ClassesList(PerkTree tree, Font font) {
        this.order = ClassVisibility.circleOrder(tree);
        this.font = font;
    }

    /** Lay out for this screen; returns the list's left edge (the card keeps clear of it). */
    int layout(int screenW) {
        this.screenW = screenW;
        this.top = SkyView.HEADER + TOP_GAP;
        int widest = 0;
        for (PerkTree.PerkClass c : order) widest = Math.max(widest, font.width(c.name()));
        widest = Math.max(widest, (int) (font.width(heading()) * HEAD_SCALE));
        left = screenW - RIGHT - EYE_W - EYE_GAP - widest;
        return left - 6;
    }

    private static Component heading() {
        return Component.translatable("gui.dungeontrain.perks.classes");
    }

    /** The class whose name is under the mouse, or null. */
    String classAt(double mx, double my) {
        if (mx < left - 4 || mx > nameRight() + 2) return null;
        return rowAt(my);
    }

    /** The shown class whose hide icon is under the mouse, or null. */
    String eyeAt(double mx, double my, Set<String> off) {
        int ex = nameRight() + EYE_GAP;
        if (mx < ex - 2 || mx > ex + EYE_W + 2) return null;
        String id = rowAt(my);
        return id != null && !off.contains(id) ? id : null;
    }

    private String rowAt(double my) {
        int y0 = top + HEAD;
        if (my < y0) return null;
        int i = (int) ((my - y0) / ROW);
        return i >= 0 && i < order.size() ? order.get(i).id() : null;
    }

    private int nameRight() {
        return screenW - RIGHT - EYE_W - EYE_GAP;
    }

    /**
     * @param marked   the selected class, or the class of the perk clicked to stay in the bar: a dash in
     *                 its colour comes out to the left of its name
     * @param eyeHover the class whose hide icon is under the mouse, drawn brighter
     */
    void render(GuiGraphics g, Set<String> off, String hovered, String marked, String hoverMarked, String eyeHover,
                double fade) {
        if (order.isEmpty()) return;
        int right = nameRight();
        int a = (int) (fade * 255);
        String head = heading().getString().toUpperCase(Locale.ROOT);
        g.pose().pushPose();
        g.pose().translate(right - font.width(head) * HEAD_SCALE, top, 0);
        g.pose().scale(HEAD_SCALE, HEAD_SCALE, 1F);
        g.drawString(font, head, 0, 0, ((int) (a * 0.7) << 24) | (SkyPalette.MUTED & 0xFFFFFF), true);
        g.pose().popPose();
        int y = top + HEAD;
        for (PerkTree.PerkClass c : order) {
            boolean shown = !off.contains(c.id());
            boolean hov = c.id().equals(hovered);
            int colour = shown ? c.colour() : SkyPalette.MUTED;
            double alpha = shown ? 1.0 : hov ? 0.9 : 0.55;
            g.drawString(font, c.name(), right - font.width(c.name()), y + 1, ((int) (a * alpha) << 24) | (colour & 0xFFFFFF), true);
            if (shown) renderEye(g, right + EYE_GAP, y + 1 + (font.lineHeight - EYE_H) / 2 - 1, c.id().equals(eyeHover), fade);
            // the clicked perk's class: a dash in its colour; the hovered perk's class: a grey one
            int dash = c.id().equals(marked) ? c.colour() : c.id().equals(hoverMarked) ? SkyPalette.MUTED : 0;
            if (dash != 0) {
                double x1 = right - font.width(c.name()) - 4, mid = y + 1 + font.lineHeight / 2.0 - 1;
                SkyDraw.line(g, x1 - DASH, mid, x1, mid, 1.5, SkyDraw.withAlpha(dash, fade), SkyDraw.withAlpha(dash, fade));
            }
            y += ROW;
        }
    }

    /** An eye with a slash through it: "hide this class". */
    private static void renderEye(GuiGraphics g, int x, int y, boolean hot, double fade) {
        int c = SkyDraw.withAlpha(hot ? SkyPalette.FG : SkyPalette.MUTED, (hot ? 1.0 : 0.7) * fade);
        g.fill(x + 2, y, x + 5, y + 1, c);
        g.fill(x + 1, y + 1, x + 2, y + 2, c);
        g.fill(x + 5, y + 1, x + 6, y + 2, c);
        g.fill(x, y + 2, x + 1, y + 3, c);
        g.fill(x + 3, y + 2, x + 4, y + 3, c);
        g.fill(x + 6, y + 2, x + 7, y + 3, c);
        g.fill(x + 1, y + 3, x + 2, y + 4, c);
        g.fill(x + 5, y + 3, x + 6, y + 4, c);
        g.fill(x + 2, y + 4, x + 5, y + 5, c);
        SkyDraw.line(g, x - 0.5, y + EYE_H + 0.5, x + EYE_W + 0.5, y - 0.5, 1.0, c, c);
    }

    static Component hideTooltip() {
        return Component.translatable("gui.dungeontrain.perks.classes.hide");
    }
}
