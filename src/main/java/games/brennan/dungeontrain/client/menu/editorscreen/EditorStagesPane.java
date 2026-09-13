package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * The Stages tab on screen: {@link EditorStagesPage}'s titles row pinned at the top, its stage rows
 * under it with their own scroll, and the selected stage outlined. The same shape as the Settings
 * pane, plus the one fixed row.
 */
final class EditorStagesPane {

    static final int ROW_H = EditorDetailPane.ROW_H;
    /** Under the shown stage's row, so it reads as chosen even when the pointer is elsewhere. */
    static final int SELECTED_FILL = 0x50FFCC33;

    private int scroll;

    /** The whole pane: the left column, from the top of the search row (there is none here) to the grid's bottom. */
    static InventoryEditorLayout.Rect rect(InventoryEditorLayout layout) {
        return EditorSettingsPane.rect(layout);
    }

    /** The titles row: the pane's first slot. */
    static InventoryEditorLayout.Rect headerRect(InventoryEditorLayout layout) {
        InventoryEditorLayout.Rect r = rect(layout);
        return new InventoryEditorLayout.Rect(r.x(), r.y(), r.w(), Math.min(ROW_H, r.h()));
    }

    /** The stage rows' rectangle: the pane less the titles row. */
    static InventoryEditorLayout.Rect listRect(InventoryEditorLayout layout) {
        InventoryEditorLayout.Rect r = rect(layout);
        return new InventoryEditorLayout.Rect(r.x(), r.y() + ROW_H, r.w(), Math.max(0, r.h() - ROW_H));
    }

    static List<EditorStagesPage.Row> rows(EditorRosterIndex index) {
        return EditorStagesPage.rows(index.stages(), EditorScreenState.stageSort(), EditorScreenState::selectStage);
    }

    static CommandMenuEntry header() {
        return EditorStagesPage.header(EditorScreenState.stageSort(), EditorScreenState::sortStages);
    }

    void render(GuiGraphics g, Font font, EditorScreenTheme theme, InventoryEditorLayout layout,
                EditorRosterIndex index, int mouseX, int mouseY) {
        InventoryEditorLayout.Rect whole = rect(layout);
        g.fill(whole.x() - 1, whole.y() - 1, whole.right() + 1, whole.bottom() + 1, theme.subPanel());
        String note = emptyNote(index);
        if (note != null) {
            g.drawString(font, note, whole.x() + 4, whole.y() + 4, 0xFFFFFFFF, true);
            return;
        }
        InventoryEditorLayout.Rect h = headerRect(layout);
        CommandMenuEntry header = header();
        int headerSub = h.contains(mouseX, mouseY) ? MenuRowPainter.hitCell(header, mouseX, h.x(), h.right()) : -1;
        MenuRowPainter.drawRow(g, font, header, h.x(), h.y(), h.right(), ROW_H - 1, 0, headerSub >= 0, headerSub, null);
        InventoryEditorLayout.Rect r = listRect(layout);
        List<EditorStagesPage.Row> rows = rows(index);
        EditorRosterPacket.StageEntry shown = EditorScreenState.effectiveStage(index);
        String shownId = shown == null ? "" : shown.id();
        int visible = visibleRows(r);
        scroll = clamp(scroll, rows.size(), visible);
        int hoveredRow = rowAt(mouseX, mouseY, r, rows.size(), visible);
        for (int k = 0; k < visible && scroll + k < rows.size(); k++) {
            int idx = scroll + k;
            EditorStagesPage.Row row = rows.get(idx);
            int top = r.y() + k * ROW_H;
            boolean hov = idx == hoveredRow;
            int hoveredSub = hov ? MenuRowPainter.hitCell(row.entry(), mouseX, r.x(), r.right()) : -1;
            boolean chosen = shownId.equalsIgnoreCase(row.stageId());
            // The shown stage reads as chosen, not merely outlined: a tint under the row and the
            // browser's selection border around it.
            if (chosen) g.fill(r.x(), top, r.right(), top + ROW_H - 1, SELECTED_FILL);
            MenuRowPainter.drawRow(g, font, row.entry(), r.x(), top, r.right(), ROW_H - 1, idx, hov, hoveredSub, null);
            if (chosen) g.renderOutline(r.x(), top, r.w(), ROW_H - 1, TemplateTilePainter.BORDER_SELECTED);
        }
        drawScrollbar(g, r, rows.size(), visible);
    }

    /** No roster yet is "loading"; a roster with no stages is not. */
    static String emptyNote(EditorRosterIndex index) {
        if (index == null || index.isEmpty()) return EditorScreenLang.text(EditorScreenLang.NO_ROSTER);
        if (index.stages().isEmpty()) return EditorScreenLang.text(EditorScreenLang.STAGES_NONE);
        return null;
    }

    /** A click on a title resorts; a click anywhere on a row selects its stage. True when either was hit. */
    boolean mouseClicked(InventoryEditorLayout layout, EditorRosterIndex index, double mouseX, double mouseY) {
        if (emptyNote(index) != null) return false;
        InventoryEditorLayout.Rect h = headerRect(layout);
        if (h.contains(mouseX, mouseY)) {
            CommandMenuEntry header = header();
            int sub = MenuRowPainter.hitCell(header, (int) mouseX, h.x(), h.right());
            if (sub < 0) return false;
            CommandMenuEntry cell = MenuRowPainter.cellsOf(header)[sub];
            if (cell instanceof CommandMenuEntry.ClientAction action) action.action().run();
            return true;
        }
        InventoryEditorLayout.Rect r = listRect(layout);
        List<EditorStagesPage.Row> rows = rows(index);
        int idx = rowAt(mouseX, mouseY, r, rows.size(), visibleRows(r));
        if (idx < 0) return false;
        EditorScreenState.selectStage(rows.get(idx).stageId());
        return true;
    }

    boolean over(InventoryEditorLayout layout, double mouseX, double mouseY) {
        return layout != null && rect(layout).contains(mouseX, mouseY);
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

    private int rowAt(double mx, double my, InventoryEditorLayout.Rect r, int count, int visible) {
        if (!r.contains(mx, my)) return -1;
        int k = (int) ((my - r.y()) / ROW_H);
        int idx = scroll + k;
        return k < visible && idx < count ? idx : -1;
    }

    private void drawScrollbar(GuiGraphics g, InventoryEditorLayout.Rect r, int count, int visible) {
        if (count <= visible || visible <= 0) return;
        int thumbH = Math.max(6, r.h() * visible / count);
        int thumbY = r.y() + (r.h() - thumbH) * scroll / Math.max(1, count - visible);
        g.fill(r.right() - 2, r.y(), r.right(), r.bottom(), 0x40FFFFFF);
        g.fill(r.right() - 2, thumbY, r.right(), thumbY + thumbH, 0xC0FFEEBB);
    }
}
