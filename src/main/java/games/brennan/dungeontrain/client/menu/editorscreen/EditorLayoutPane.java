package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The Layout tab on screen: {@link EditorLayoutPage}'s rows down the left of the panel, with the
 * selection outlined and the standing plot marked, scrolling three rows a notch.
 *
 * <p>Rows are rebuilt only when the roster snapshot or the folded-section set is a different
 * object — both are replaced wholesale, never mutated — so a few hundred rows cost one build per
 * refresh rather than one per frame.</p>
 */
final class EditorLayoutPane {

    /** A click landed on cell {@code sub} of {@code row}; {@code cellRect} is where that cell was drawn. */
    record Hit(EditorLayoutPage.Row row, int sub, CommandMenuEntry cell, InventoryEditorLayout.Rect cellRect) {}

    static final int ROW_H = EditorDetailPane.ROW_H;
    static final int INDENT = 10;
    static final int SCROLL_ROWS = 3;

    private final Consumer<VariantKey> select;
    private EditorRosterIndex lastIndex;
    private Set<String> lastCollapsed;
    private Set<String> lastCollapsedGroups;
    private EditorLayoutPage.Query lastQuery;
    private List<EditorLayoutPage.Row> rows = List.of();
    private int scroll;

    EditorLayoutPane(Consumer<VariantKey> select) {
        this.select = select;
    }

    /** The grid rect: the filter bar sits above it, as it does above the tiles. */
    static InventoryEditorLayout.Rect rect(InventoryEditorLayout layout) {
        return layout.grid();
    }

    /**
     * The rows for this roster, fold state and query, rebuilt only when one of them changed — the
     * first two by identity (both are replaced, never mutated), the query by value.
     */
    List<EditorLayoutPage.Row> rows(EditorRosterIndex index) {
        Set<String> collapsed = EditorScreenState.collapsedSections();
        Set<String> groups = EditorScreenState.collapsedGroups();
        EditorLayoutPage.Query query = EditorLayoutPage.Query.current();
        if (index != lastIndex || collapsed != lastCollapsed || groups != lastCollapsedGroups || !query.equals(lastQuery)) {
            rows = EditorLayoutPage.rows(index, new EditorLayoutPage.Folds(collapsed, groups), query, select,
                EditorScreenState::toggleSection, EditorScreenState::toggleGroup);
            lastIndex = index;
            lastCollapsed = collapsed;
            lastCollapsedGroups = groups;
            lastQuery = query;
        }
        return rows;
    }

    void resetScroll() {
        scroll = 0;
    }

    void render(GuiGraphics g, Font font, EditorScreenTheme theme, InventoryEditorLayout layout,
                EditorRosterIndex index, VariantKey selection, VariantKey standing, int mouseX, int mouseY) {
        InventoryEditorLayout.Rect r = rect(layout);
        g.fill(r.x() - 1, r.y() - 1, r.right() + 1, r.bottom() + 1, theme.subPanel());
        List<EditorLayoutPage.Row> all = rows(index);
        if (all.isEmpty()) {
            g.drawString(font, EditorScreenLang.text(EditorScreenLang.NO_ROSTER), r.x() + 4, r.y() + 4, 0xFFFFFFFF, true);
            return;
        }
        int visible = visibleRows(r);
        scroll = clamp(scroll, all.size(), visible);
        int hoveredRow = rowAt(mouseX, mouseY, r, all, visible);
        for (int k = 0; k < visible && scroll + k < all.size(); k++) {
            int idx = scroll + k;
            EditorLayoutPage.Row row = all.get(idx);
            int left = r.x() + row.depth() * INDENT;
            int top = r.y() + k * ROW_H;
            boolean hov = idx == hoveredRow;
            int hoveredSub = hov ? MenuRowPainter.hitCell(row.entry(), mouseX, left, r.right()) : -1;
            MenuRowPainter.drawRow(g, font, row.entry(), left, top, r.right(), ROW_H - 1, idx, hov, hoveredSub, null);
            if (row.isHeader()) continue;
            if (selection != null && selection.equals(row.key())) {
                g.renderOutline(left, top, r.right() - left, ROW_H - 1, TemplateTilePainter.BORDER_SELECTED);
            }
            if (standing != null && standing.sameTemplate(row.key())) {
                int my = top + (ROW_H - 1) / 2;
                g.fill(left + 1, my - 1, left + 3, my + 1, TemplateTilePainter.HERE);
            }
        }
        drawScrollbar(g, r, all.size(), visible);
    }

    private void drawScrollbar(GuiGraphics g, InventoryEditorLayout.Rect r, int count, int visible) {
        if (count <= visible || visible <= 0) return;
        int thumbH = Math.max(6, r.h() * visible / count);
        int thumbY = r.y() + (r.h() - thumbH) * scroll / Math.max(1, count - visible);
        g.fill(r.right() - 2, r.y(), r.right(), r.bottom(), 0x40FFFFFF);
        g.fill(r.right() - 2, thumbY, r.right(), thumbY + thumbH, 0xC0FFEEBB);
    }

    /** The cell under the point, or null over nothing clickable. */
    Hit hitTest(InventoryEditorLayout layout, EditorRosterIndex index, double mouseX, double mouseY) {
        InventoryEditorLayout.Rect r = rect(layout);
        List<EditorLayoutPage.Row> all = rows(index);
        int idx = rowAt(mouseX, mouseY, r, all, visibleRows(r));
        if (idx < 0) return null;
        EditorLayoutPage.Row row = all.get(idx);
        int left = r.x() + row.depth() * INDENT;
        int sub = MenuRowPainter.hitCell(row.entry(), (int) mouseX, left, r.right());
        if (sub < 0) return null;
        CommandMenuEntry[] cells = MenuRowPainter.cellsOf(row.entry());
        int[] span = MenuRowPainter.cellSpan(row.entry(), sub, left, r.right());
        int top = r.y() + (idx - scroll) * ROW_H;
        return new Hit(row, sub, cells[Math.min(sub, cells.length - 1)],
            new InventoryEditorLayout.Rect(span[0], top, span[1] - span[0], ROW_H - 1));
    }

    /**
     * Act on a click. A typed value goes through the inline field over its own cell — the modal
     * host's typing path would close the whole screen on Enter; everything else dispatches through
     * the host so a stepper stays open and a picker opens as a modal.
     */
    boolean mouseClicked(InventoryEditorLayout layout, EditorRosterIndex index, double mouseX, double mouseY,
                         EditorModalHost modal, InlineEdit inlineEdit) {
        Hit hit = hitTest(layout, index, mouseX, mouseY);
        if (hit == null) return false;
        if (hit.cell() instanceof CommandMenuEntry.TypeArg type) {
            inlineEdit.begin(type.commandPrefix(), type.label(), hit.cellRect());
            return true;
        }
        modal.dispatch(hit.row().entry(), hit.sub());
        return true;
    }

    /** What the hovered cell is for, or null. */
    String tooltipAt(InventoryEditorLayout layout, EditorRosterIndex index, int mouseX, int mouseY) {
        Hit hit = hitTest(layout, index, mouseX, mouseY);
        if (hit == null) return null;
        if (hit.row().isHeader()) return EditorScreenLang.text(EditorScreenLang.LAYOUT_SECTION_TIP);
        return switch (hit.sub()) {
            case 0 -> EditorScreenLang.text(EditorScreenLang.LAYOUT_SECTION_TIP);
            case 2 -> EditorScreenLang.text(EditorScreenLang.SHEET_WEIGHT_DOWN);
            case 3 -> hit.cell() instanceof CommandMenuEntry.TypeArg
                ? EditorScreenLang.text(EditorScreenLang.SHEET_WEIGHT_TOOLTIP) : null;
            case 4 -> EditorScreenLang.text(EditorScreenLang.SHEET_WEIGHT_UP);
            case 5 -> EditorScreenLang.text(EditorScreenLang.SHEET_STAGE_TOOLTIP);
            default -> null;
        };
    }

    boolean over(InventoryEditorLayout layout, double mouseX, double mouseY) {
        return rect(layout).contains(mouseX, mouseY);
    }

    boolean scrollBy(int dir) {
        scroll = Math.max(0, scroll + dir * SCROLL_ROWS);
        return true;
    }

    private static int visibleRows(InventoryEditorLayout.Rect r) {
        return Math.max(1, r.h() / ROW_H);
    }

    private static int clamp(int scroll, int rows, int visible) {
        return Math.max(0, Math.min(scroll, Math.max(0, rows - visible)));
    }

    private int rowAt(double mx, double my, InventoryEditorLayout.Rect r, List<EditorLayoutPage.Row> all, int visible) {
        if (!r.contains(mx, my)) return -1;
        int k = (int) ((my - r.y()) / ROW_H);
        int idx = scroll + k;
        return k < visible && idx < all.size() ? idx : -1;
    }
}
