package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkTree;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Classes list down the right edge (the web page's {@code renderLockedList}): every class in circle
 * order, in its colour when shown and muted when hidden, with a dot per perk before its name (filled once
 * a point is in it, a ring once unlocked, dim while locked). Hovering a hidden class previews it on the
 * sky; hovering any class makes its perks glow. A click on a hidden class shows it; on a shown one it
 * selects it (the bar keeps showing it).
 *
 * <p>The selected class (or the class of the perk kept in the bar) sits in a pill of its colour with dark
 * text; the hovered one gets a grey outline. A shown class, while hovered or selected, gets an eye with a
 * slash after its name that hides it; the heading gets one while hovered that hides them all. Every row
 * leaves room for it so the names never shift.
 */
@OnlyIn(Dist.CLIENT)
final class ClassesList {

    private static final int RIGHT = 10, TOP_GAP = 8, ROW = 12, HEAD = 12;
    private static final float HEAD_SCALE = 0.75F;
    /** The hide icon's column after the names. */
    static final int EYE_W = 7, EYE_H = 5;
    private static final int EYE_GAP = 5;
    private static final int DOT_GAP = 5, DOT_PITCH = 6, PILL_PAD = 3;
    private static final double DARK_TEXT = 0.72, DARK_LOCKED = 0.4;

    private final PerkTree tree;
    private final List<PerkTree.PerkClass> order;
    private final Font font;
    private int top;
    private int left;
    private int screenW;

    ClassesList(PerkTree tree, Font font) {
        this.tree = tree;
        this.order = ClassVisibility.circleOrder(tree);
        this.font = font;
    }

    /** Lay out for this screen; returns the list's left edge (the card keeps clear of it). */
    int layout(int screenW) {
        this.screenW = screenW;
        this.top = SkyView.HEADER + TOP_GAP;
        int widest = (int) (font.width(heading()) * HEAD_SCALE);
        for (PerkTree.PerkClass c : order) widest = Math.max(widest, rowWidth(c));
        left = nameRight() - widest;
        return left - PILL_PAD - 4;
    }

    private int rowWidth(PerkTree.PerkClass c) {
        int dots = dotsWidth(c);
        return font.width(c.name()) + (dots > 0 ? DOT_GAP + dots : 0);
    }

    private int dotsWidth(PerkTree.PerkClass c) {
        int n = tree.perksOf(c.id()).size();
        return n > 0 ? n * DOT_PITCH - 2 : 0;
    }

    private int eyeX() {
        return screenW - RIGHT - EYE_W;
    }

    private int nameRight() {
        return eyeX() - EYE_GAP;
    }

    private static Component heading() {
        return Component.translatable("gui.dungeontrain.perks.classes");
    }

    /** The class whose name or dots are under the mouse, or null. */
    String classAt(double mx, double my) {
        if (mx < left - PILL_PAD || mx > nameRight() + 2) return null;
        return rowAt(my);
    }

    /** The shown class whose hide icon is under the mouse, or null. */
    String eyeAt(double mx, double my, Set<String> off) {
        if (mx <= nameRight() + 2 || mx > eyeX() + EYE_W + 2) return null;
        String id = rowAt(my);
        return id != null && !off.contains(id) ? id : null;
    }

    /** The class on the row under the mouse, icon, name or dots, or null. */
    String rowHover(double mx, double my) {
        if (mx < left - PILL_PAD - 1 || mx > eyeX() + EYE_W + PILL_PAD) return null;
        return rowAt(my);
    }

    /** The mouse is on the heading row. */
    boolean headingAt(double mx, double my) {
        return my >= top - 2 && my < top + HEAD - 2 && mx >= left - PILL_PAD - 1 && mx <= eyeX() + EYE_W + PILL_PAD;
    }

    /** The heading's hide-all icon is under the mouse (and some class is shown to hide). */
    boolean headEyeAt(double mx, double my, Set<String> off) {
        return headingAt(mx, my) && mx > nameRight() + 2 && off.size() < order.size();
    }

    /** Every class id in the list. */
    Set<String> ids() {
        Set<String> out = new HashSet<>();
        for (PerkTree.PerkClass c : order) out.add(c.id());
        return out;
    }

    private String rowAt(double my) {
        int y0 = top + HEAD;
        if (my < y0) return null;
        int i = (int) ((my - y0) / ROW);
        return i >= 0 && i < order.size() ? order.get(i).id() : null;
    }

    /**
     * @param hovered  the class whose name is under the mouse (a hidden one reads brighter)
     * @param marked   the selected class, or the class of the perk kept in the bar: a pill of its colour
     * @param hoverMarked the class of the hovered perk: a grey outline
     * @param rowHover the class whose row is under the mouse: its icon shows, and a grey outline
     * @param selectedClass the class clicked to stay: its icon shows
     * @param eyeHover the class whose hide icon is under the mouse, drawn brighter
     * @param head 0 the heading is not hovered, 1 hovered (its hide-all icon shows), 2 its icon hovered
     */
    void render(GuiGraphics g, PerkMenuSnapshot snap, Set<String> off, String hovered, String marked, String hoverMarked,
                String rowHover, String selectedClass, String eyeHover, int head, double fade) {
        if (order.isEmpty()) return;
        int a = (int) (fade * 255);
        String title = heading().getString().toUpperCase(Locale.ROOT);
        g.pose().pushPose();
        g.pose().translate(nameRight() - font.width(title) * HEAD_SCALE, top, 0);
        g.pose().scale(HEAD_SCALE, HEAD_SCALE, 1F);
        g.drawString(font, title, 0, 0, ((int) (a * 0.7) << 24) | (SkyPalette.MUTED & 0xFFFFFF), true);
        g.pose().popPose();
        if (head > 0 && off.size() < order.size()) renderEye(g, eyeX(), top + 1, head == 2, SkyPalette.MUTED, fade);
        int y = top + HEAD;
        for (PerkTree.PerkClass c : order) {
            renderRow(g, snap, c, y, !off.contains(c.id()), c.id().equals(hovered), c.id().equals(marked),
                c.id().equals(hoverMarked) || c.id().equals(rowHover),
                c.id().equals(rowHover) || c.id().equals(selectedClass), c.id().equals(eyeHover), fade);
            y += ROW;
        }
    }

    private void renderRow(GuiGraphics g, PerkMenuSnapshot snap, PerkTree.PerkClass c, int y, boolean shown, boolean hov,
                           boolean pill, boolean outline, boolean eye, boolean eyeHot, double fade) {
        int x0 = nameRight() - rowWidth(c) - PILL_PAD, x1 = eyeX() + EYE_W + PILL_PAD, y0 = y - 1, y1 = y + ROW - 2;
        int nameX = nameRight() - font.width(c.name());
        int dark = SkyDraw.mix(c.colour(), 0xFF000000, DARK_TEXT);
        if (pill) {
            int fill = SkyDraw.withAlpha(c.colour(), fade);
            g.fill(x0 + 1, y0, x1 - 1, y1, fill);
            g.fill(x0, y0 + 1, x0 + 1, y1 - 1, fill);
            g.fill(x1 - 1, y0 + 1, x1, y1 - 1, fill);
        } else if (outline) {
            int edge = SkyDraw.withAlpha(SkyPalette.MUTED, 0.8 * fade);
            g.fill(x0 + 1, y0, x1 - 1, y0 + 1, edge);
            g.fill(x0 + 1, y1 - 1, x1 - 1, y1, edge);
            g.fill(x0, y0 + 1, x0 + 1, y1 - 1, edge);
            g.fill(x1 - 1, y0 + 1, x1, y1 - 1, edge);
        }
        int colour = pill ? dark : shown ? c.colour() : SkyPalette.MUTED;
        double alpha = pill || shown ? 1.0 : hov ? 0.9 : 0.55;
        g.drawString(font, c.name(), nameX, y + 1, SkyDraw.withAlpha(colour, alpha * fade), !pill);
        if (shown && eye) renderEye(g, eyeX(), y + 1 + (font.lineHeight - EYE_H) / 2 - 1, eyeHot, pill ? dark : SkyPalette.MUTED, fade);
        renderDots(g, snap, c, nameX - DOT_GAP, y + 1 + font.lineHeight / 2.0 - 1,
            pill ? dark : c.colour(), pill, pill || outline, shown ? 1.0 : 0.55, fade);
    }

    /**
     * Dots ending at {@code right}. Selected or hovered: one per perk, filled with a point in it, a ring
     * once unlocked, small and dim while locked. Otherwise only the filled ones, for the active perks.
     */
    private void renderDots(GuiGraphics g, PerkMenuSnapshot snap, PerkTree.PerkClass c, int right, double cy, int colour,
                            boolean onPill, boolean full, double alpha, double fade) {
        int locked = onPill ? SkyDraw.mix(c.colour(), 0xFF000000, DARK_LOCKED) : 0xFF3A3F5A;
        List<PerkTree.Perk> perks = tree.perksOf(c.id());
        if (!full) perks = perks.stream().filter(p -> snap.points(p.id()) > 0).toList();
        if (perks.isEmpty()) return;
        int x = right - (perks.size() * DOT_PITCH - 2);
        int i = 0;
        for (PerkTree.Perk p : perks) {
            double cx = x + 2 + i++ * DOT_PITCH;
            int level = snap.unlockLevel(p.id());
            if (snap.points(p.id()) > 0) SkyDraw.disc(g, cx, cy, 2.0, SkyDraw.withAlpha(colour, alpha * fade));
            else if (level > 0) SkyDraw.ring(g, cx, cy, 1.6, 0.9, SkyDraw.withAlpha(colour, alpha * fade));
            else SkyDraw.disc(g, cx, cy, 1.3, SkyDraw.withAlpha(locked, fade));
        }
    }

    /** An eye with a slash through it: "hide". */
    static void renderEye(GuiGraphics g, int x, int y, boolean hot, int idle, double fade) {
        int c = SkyDraw.withAlpha(hot ? SkyPalette.FG : idle, (hot ? 1.0 : 0.8) * fade);
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

    static Component hideAllTooltip() {
        return Component.translatable("gui.dungeontrain.perks.classes.hide_all");
    }
}
