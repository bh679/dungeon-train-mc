package games.brennan.dungeontrain.client.perks;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A row of blocks for points: {@code filled} solid, then {@code open} outlined, then {@code locked} dark.
 * A perk's graph is its points, its empty room, and the next unlock; a class's is its active, unlocked
 * and locked perks. The blocks share the width they are given.
 *
 * <ul>
 *   <li>More than {@link #MAX_SEGMENTS} blocks: the first {@link #MAX_SEGMENTS} and a "+".</li>
 *   <li>{@link #MAX_SEGMENTS} or more filled, or blocks narrower than {@link #MIN_SEGMENT}: a progress
 *       bar (filled solid, open faint) with {@code filled/total} beside it.</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
final class PointsGraph {

    static final int MAX_SEGMENTS = 20;
    static final int MIN_SEGMENT = 3;
    static final int GAP = 1, PLUS_W = 6;
    private static final int LOCKED = 0x2A2F4A;

    enum Kind { FILLED, OPEN, LOCKED, PLUS }

    /** How the graph lays out in a given width; pure, so tests can check the rules. */
    record Layout(boolean bar, int segments, int segmentWidth, boolean plus) {}

    final int filled, open, locked, total;

    /** {@code total} is the number after the slash when it draws as a bar. */
    PointsGraph(int filled, int open, int locked, int total) {
        this.filled = Math.max(0, filled);
        this.open = Math.max(0, open);
        this.locked = Math.max(0, locked);
        this.total = total;
    }

    Layout layout(int width) {
        int count = filled + open + locked;
        if (filled >= MAX_SEGMENTS || count == 0) return new Layout(count > 0, 0, 0, false);
        boolean plus = count > MAX_SEGMENTS;
        int shown = Math.min(count, MAX_SEGMENTS);
        int room = width - (plus ? PLUS_W + GAP : 0) - (shown - 1) * GAP;
        int w = room / shown;
        if (w < MIN_SEGMENT) return new Layout(true, 0, 0, false);
        return new Layout(false, shown, w, plus);
    }

    /** What block {@code dx} px from the graph's left edge falls on, or null (a gap, past the end, or a bar). */
    Kind at(int width, double dx) {
        int i = indexAt(width, dx);
        return i < 0 ? null : i == layout(width).segments() ? Kind.PLUS : kind(i);
    }

    /** The block index under {@code dx} (the "+" is one past the last block), or -1. */
    int indexAt(int width, double dx) {
        Layout l = layout(width);
        if (l.bar() || dx < 0) return -1;
        int pitch = l.segmentWidth() + GAP;
        int i = (int) (dx / pitch);
        if (i < l.segments()) return dx - i * pitch < l.segmentWidth() ? i : -1;
        return l.plus() && dx < l.segments() * pitch + PLUS_W ? l.segments() : -1;
    }

    private Kind kind(int i) {
        return i < filled ? Kind.FILLED : i < filled + open ? Kind.OPEN : Kind.LOCKED;
    }

    /**
     * Draw at {@code (x, y)}, {@code width} by {@code height}. {@code hovered} is the block under the
     * mouse to light up, or -1. A bar's {@code filled/total} uses {@code textScale}.
     */
    void render(GuiGraphics g, Font font, int x, int y, int width, int height, int colour, double alpha,
                int hovered, float textScale) {
        int a = (int) (alpha * 255);
        int on = (a << 24) | (colour & 0xFFFFFF);
        int dark = ((int) (a * 0.9) << 24) | LOCKED;
        Layout l = layout(width);
        if (l.bar()) {
            renderBar(g, font, x, y, width, height, colour, alpha, dark, textScale);
            return;
        }
        int pitch = l.segmentWidth() + GAP;
        for (int i = 0; i < l.segments(); i++) {
            int sx = x + i * pitch, w = l.segmentWidth();
            Kind k = kind(i);
            if (k == Kind.FILLED) g.fill(sx, y, sx + w, y + height, on);
            else if (k == Kind.OPEN) g.renderOutline(sx, y, w, height, on);
            else g.fill(sx, y, sx + w, y + height, dark);
            if (i == hovered) g.renderOutline(sx - 1, y - 1, w + 2, height + 2, (a << 24) | 0xFFFFFF);
        }
        if (l.plus()) {
            int px = x + l.segments() * pitch + 1, mid = y + height / 2;
            g.fill(px, mid, px + 5, mid + 1, on);
            g.fill(px + 2, mid - 2, px + 3, mid + 3, on);
        }
    }

    private void renderBar(GuiGraphics g, Font font, int x, int y, int width, int height, int colour, double alpha,
                           int dark, float textScale) {
        String label = filled + "/" + total;
        int textW = (int) Math.ceil(font.width(label) * textScale) + 3;
        int barW = Math.max(4, width - textW);
        int count = Math.max(1, filled + open + locked);
        g.fill(x, y, x + barW, y + height, dark);
        int openEnd = x + (int) Math.round(barW * (filled + open) / (double) count);
        int fillEnd = x + (int) Math.round(barW * filled / (double) count);
        g.fill(x, y, openEnd, y + height, SkyDraw.withAlpha(colour, 0.35 * alpha));
        g.fill(x, y, fillEnd, y + height, SkyDraw.withAlpha(colour, alpha));
        g.pose().pushPose();
        g.pose().translate(x + barW + 3, y + (height - font.lineHeight * textScale) / 2.0 + textScale, 0);
        g.pose().scale(textScale, textScale, 1F);
        g.drawString(font, label, 0, 0, SkyDraw.withAlpha(SkyPalette.FG, alpha), false);
        g.pose().popPose();
    }
}
