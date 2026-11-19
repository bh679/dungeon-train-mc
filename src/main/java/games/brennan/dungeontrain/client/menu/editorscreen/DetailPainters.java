package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * The painters the template detail pane and the stage detail pane share: the icon strip, a paged
 * row list, and the colour tint a sprite is drawn through.
 */
final class DetailPainters {

    /** The hover tint for an enabled icon: the sprite goes black on its lit cell. */
    static final int HOVER_INK = 0xFF000000;
    /** The fill of a hovered icon whose action is destructive. */
    static final int DANGER_HOVER = 0xC0FF5544;

    private DetailPainters() {}

    /**
     * One icon strip: a cell per icon at {@code iconX[i]}, each {@code iconCell} square on row
     * {@code y}, filled idle / hover / danger-hover / disabled and the sprite blitted through
     * {@code tintOf} — the pane's own rule (a disabled icon dims, a hovered one inks, the template
     * pane's Save and Submit pulse their state) — then the tint reset.
     *
     * @param hoveredIndex the hovered icon's index, or -1
     * @param danger       whether icon {@code i} is destructive, so its hover fill goes red
     * @param tintOf       the ARGB tint to draw icon {@code i} through, or 0 for none
     */
    static void drawIconStrip(GuiGraphics g, List<EditorScreenActions.Icon> icons, int[] iconX, int iconCell,
                              int y, int hoveredIndex, IntPredicate danger, IntFunction<Integer> tintOf) {
        for (int i = 0; i < icons.size(); i++) {
            EditorScreenActions.Icon icon = icons.get(i);
            int x = iconX[i];
            boolean hov = hoveredIndex == i;
            int fill = !icon.enabled() ? EditorDetailPane.DISABLED
                : hov ? (danger.test(i) ? DANGER_HOVER : MenuRowPainter.CELL_HOVER) : MenuRowPainter.CELL_IDLE;
            g.fill(x, y, x + iconCell, y + iconCell, fill);
            int tint = tintOf.apply(i);
            if (tint != 0) tint(g, tint);
            int sprite = Math.min(EditorDetailPane.ICON_SIZE, iconCell);
            g.blitSprite(EditorIcons.forAction(icon.id()), x + (iconCell - sprite) / 2,
                y + (iconCell - sprite) / 2, sprite, sprite);
            g.setColor(1f, 1f, 1f, 1f);
        }
    }

    /**
     * Rows {@code [first, end)} of {@code rows} down {@code r}, one {@code rowH} each, scissored
     * to the rect; the hovered row lit, with {@code hoveredSub} the lit cell within it (or -1).
     */
    static void drawRowList(GuiGraphics g, Font font, List<? extends CommandMenuEntry> rows,
                            InventoryEditorLayout.Rect r, int rowH, int first, int end,
                            int hoveredIndex, int hoveredSub) {
        g.enableScissor(r.x(), r.y(), r.right(), r.bottom());
        for (int idx = first; idx < end; idx++) {
            int top = r.y() + (idx - first) * rowH;
            boolean hov = hoveredIndex == idx;
            MenuRowPainter.drawRow(g, font, rows.get(idx), r.x(), top, r.right(), rowH - 1,
                idx, hov, hov ? hoveredSub : -1, null);
        }
        g.disableScissor();
    }

    /** Set the colour the next sprite is drawn through, from an ARGB int. */
    static void tint(GuiGraphics g, int argb) {
        g.setColor(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
            (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
    }
}
