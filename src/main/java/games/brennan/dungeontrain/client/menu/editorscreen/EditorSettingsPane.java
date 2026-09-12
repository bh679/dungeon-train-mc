package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.builder.BuilderBoundsState;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import games.brennan.dungeontrain.editor.PlotCategory;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * The Settings tab on screen: {@link EditorSettingsPage}'s rows painted down the left of the panel,
 * with their own scroll.
 *
 * <p>The same shape as the browser pane — lay out, render, hit-test, scroll — so the screen treats
 * each page the same way and stays out of the row arithmetic.</p>
 */
final class EditorSettingsPane {

    /** A click landed on a row's cell; {@code row} indexes the rows built for this frame. */
    record Hit(int row, int sub, CommandMenuEntry entry) {}

    static final int ROW_H = EditorDetailPane.ROW_H;

    private int scroll;

    /** The rectangle the rows fill: the left column, from the top of the search row (there is none here) to the grid's bottom. */
    static InventoryEditorLayout.Rect rect(InventoryEditorLayout layout) {
        return new InventoryEditorLayout.Rect(
            layout.grid().x(), layout.filter().y(), layout.grid().w(),
            layout.grid().bottom() - layout.filter().y());
    }

    /** The rows for the plot the player stands in — null category outside every plot. */
    static List<CommandMenuEntry> rows() {
        if (BuilderBoundsState.isInBuilderWorld()) {
            return EditorSettingsPage.builderRows(BuilderBoundsState.mirror(),
                ClientDisplayConfig.getEditorScreenTheme(), ClientDisplayConfig::setEditorScreenTheme);
        }
        VariantKey standing = EditorScreenState.standingIn();
        PlotCategory cat = standing == null ? null : standing.category();
        String name = standing == null ? "" : standing.displayName();
        return EditorSettingsPage.rows(cat, name, ClientDisplayConfig.getEditorScreenTheme(),
            ClientDisplayConfig::setEditorScreenTheme);
    }

    void render(GuiGraphics g, Font font, EditorScreenTheme theme, InventoryEditorLayout layout,
                int mouseX, int mouseY) {
        InventoryEditorLayout.Rect r = rect(layout);
        g.fill(r.x() - 1, r.y() - 1, r.right() + 1, r.bottom() + 1, theme.subPanel());
        List<CommandMenuEntry> rows = rows();
        int visible = visibleRows(r);
        scroll = clamp(scroll, rows.size(), visible);
        int hoveredRow = rowAt(mouseX, mouseY, r, rows, visible);
        int hoveredSub = hoveredRow < 0 ? 0
            : MenuRowPainter.hitCell(rows.get(hoveredRow), mouseX, r.x(), r.right());
        for (int k = 0; k < visible && scroll + k < rows.size(); k++) {
            int idx = scroll + k;
            MenuRowPainter.drawRow(g, font, rows.get(idx), r.x(), r.y() + k * ROW_H, r.right(), ROW_H - 1,
                idx, idx == hoveredRow, hoveredSub, null);
        }
    }

    /** The row cell under the point, or null. Rows are rebuilt so the hit matches what was drawn. */
    Hit hitTest(InventoryEditorLayout layout, double mouseX, double mouseY) {
        InventoryEditorLayout.Rect r = rect(layout);
        List<CommandMenuEntry> rows = rows();
        int idx = rowAt(mouseX, mouseY, r, rows, visibleRows(r));
        if (idx < 0) return null;
        int sub = MenuRowPainter.hitCell(rows.get(idx), (int) mouseX, r.x(), r.right());
        if (sub < 0) return null;
        return new Hit(idx, sub, rows.get(idx));
    }

    /** Wheel: one row per notch, held within the list. */
    boolean scrollBy(int dir) {
        scroll = Math.max(0, scroll + dir);
        return true;
    }

    private static int visibleRows(InventoryEditorLayout.Rect r) {
        return Math.max(1, r.h() / ROW_H);
    }

    private static int clamp(int scroll, int rows, int visible) {
        return Math.max(0, Math.min(scroll, Math.max(0, rows - visible)));
    }

    private int rowAt(double mx, double my, InventoryEditorLayout.Rect r,
                      List<CommandMenuEntry> rows, int visible) {
        if (!r.contains(mx, my)) return -1;
        int k = (int) ((my - r.y()) / ROW_H);
        int idx = scroll + k;
        return k < visible && idx < rows.size() ? idx : -1;
    }
}
