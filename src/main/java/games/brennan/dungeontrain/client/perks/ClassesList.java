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
 * sky; hovering any class makes its perks glow; a click shows or hides it.
 */
@OnlyIn(Dist.CLIENT)
final class ClassesList {

    private static final int RIGHT = 8, TOP_GAP = 8, ROW = 11, HEAD = 12;
    private static final float HEAD_SCALE = 0.75F;

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
        left = screenW - RIGHT - widest;
        return left - 6;
    }

    private static Component heading() {
        return Component.translatable("gui.dungeontrain.perks.classes");
    }

    /** The class under the mouse, or null. */
    String classAt(double mx, double my) {
        if (mx < left - 4 || mx > screenW - RIGHT + 2) return null;
        int y0 = top + HEAD;
        if (my < y0) return null;
        int i = (int) ((my - y0) / ROW);
        return i >= 0 && i < order.size() ? order.get(i).id() : null;
    }

    void render(GuiGraphics g, Set<String> off, String hovered, double fade) {
        if (order.isEmpty()) return;
        int right = screenW - RIGHT;
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
            y += ROW;
        }
    }
}
