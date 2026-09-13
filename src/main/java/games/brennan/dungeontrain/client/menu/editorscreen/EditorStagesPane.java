package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * The Stages tab on screen: {@link EditorStagesPage}'s rows down the left of the panel, with their
 * own scroll and the selected stage outlined. The same shape as the Settings pane.
 */
final class EditorStagesPane {

    static final int ROW_H = EditorDetailPane.ROW_H;
    /** Under the shown stage's row, so it reads as chosen even when the pointer is elsewhere. */
    static final int SELECTED_FILL = 0x50FFCC33;

    private int scroll;

    /** The rectangle the rows fill: the left column, from the top of the search row (there is none here) to the grid's bottom. */
    static InventoryEditorLayout.Rect rect(InventoryEditorLayout layout) {
        return EditorSettingsPane.rect(layout);
    }

    static List<EditorStagesPage.Row> rows(EditorRosterIndex index) {
        return EditorStagesPage.rows(index.stages(), EditorScreenState::selectStage);
    }

    void render(GuiGraphics g, Font font, EditorScreenTheme theme, InventoryEditorLayout layout,
                EditorRosterIndex index, int mouseX, int mouseY) {
        InventoryEditorLayout.Rect r = rect(layout);
        g.fill(r.x() - 1, r.y() - 1, r.right() + 1, r.bottom() + 1, theme.subPanel());
        List<EditorStagesPage.Row> rows = rows(index);
        String note = emptyNote(index);
        if (note != null) {
            g.drawString(font, note, r.x() + 4, r.y() + 4, 0xFFFFFFFF, true);
            return;
        }
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

    /** A click anywhere on a row selects its stage; true when one was hit. */
    boolean mouseClicked(InventoryEditorLayout layout, EditorRosterIndex index, double mouseX, double mouseY) {
        InventoryEditorLayout.Rect r = rect(layout);
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
