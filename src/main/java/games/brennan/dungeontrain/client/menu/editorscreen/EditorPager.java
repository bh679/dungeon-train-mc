package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The {@code <  n / N  >} row the detail panes page with — one painter and one hit-test so every
 * pane's pager reads and clicks the same.
 *
 * <p>Pure over the rect it is given: the pane decides where the row sits and which page is up; this
 * only draws the two arrow cells at the row's ends and the count between them.</p>
 */
final class EditorPager {

    /** How wide each arrow cell is, as a share of the row. */
    static final double ARROW_SHARE = 0.18;

    enum Hit { NONE, PREV, NEXT }

    private EditorPager() {}

    /** The arrow under the point, or NONE — a disabled arrow (first/last page) does not hit. */
    static Hit hit(InventoryEditorLayout.Rect r, int page, int pageCount, double mx, double my) {
        if (r == null || !r.contains(mx, my)) return Hit.NONE;
        int arrowW = arrowWidth(r);
        if (mx < r.x() + arrowW) return page > 0 ? Hit.PREV : Hit.NONE;
        if (mx >= r.right() - arrowW) return page < pageCount - 1 ? Hit.NEXT : Hit.NONE;
        return Hit.NONE;
    }

    static void draw(GuiGraphics g, Font font, InventoryEditorLayout.Rect r, int page, int pageCount, Hit hovered) {
        int top = r.y();
        int bottom = r.bottom() - 1;
        int arrowW = arrowWidth(r);
        boolean first = page == 0;
        boolean last = page >= pageCount - 1;
        drawCell(g, font, r.x(), top, r.x() + arrowW, bottom, "<", !first, hovered == Hit.PREV);
        drawCell(g, font, r.right() - arrowW, top, r.right(), bottom, ">", !last, hovered == Hit.NEXT);
        String label = (page + 1) + " / " + pageCount;
        g.drawString(font, label, (r.x() + r.right() - font.width(label)) / 2,
            top + (r.h() - 1 - font.lineHeight) / 2 + 1, EditorDetailPane.DIM_TEXT, false);
    }

    private static int arrowWidth(InventoryEditorLayout.Rect r) {
        return (int) Math.round(r.w() * ARROW_SHARE);
    }

    private static void drawCell(GuiGraphics g, Font font, int x1, int y1, int x2, int y2,
                                 String glyph, boolean enabled, boolean hov) {
        int fill = !enabled ? EditorDetailPane.DISABLED : hov ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE;
        g.fill(x1, y1, x2, y2, fill);
        int color = !enabled ? 0x80FFFFFF : hov ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF;
        g.drawString(font, glyph, (x1 + x2 - font.width(glyph)) / 2,
            y1 + (y2 - y1 - font.lineHeight) / 2 + 1, color, false);
    }
}
