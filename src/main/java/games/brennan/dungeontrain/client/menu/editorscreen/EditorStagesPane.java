package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.builder.StagePreviews;
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
    /** The column before a row's name: the stage's most-used block, drawn small enough to sit in a row. */
    static final int ICON_W = ROW_H;
    static final float ICON_SCALE = (ROW_H - 2) / 16f;
    /** Under the shown stage's row, so it reads as chosen even when the pointer is elsewhere. */
    static final int SELECTED_FILL = 0x50FFCC33;
    /** The view toggle's glyphs: what pressing it switches TO. */
    static final String GRID_GLYPH = "▦";
    static final String LIST_GLYPH = "≡";
    static final int TILE_GAP = 3;
    /** The tiles' fixed three-quarter turn — the overview's model is the one that spins. */
    static final float TILE_YAW = 35f;
    /** Every tile's roll is the same, so a stage's tile is baked once and kept. */
    static final long TILE_SEED = 0L;

    private int scroll;
    /** The tile view's scroll, in pixels. */
    private int tileScroll;
    private TemplateTileGridLayout grid;

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

    /** The view toggle: the titles row's first column, above the block icons. */
    static InventoryEditorLayout.Rect toggleRect(InventoryEditorLayout layout) {
        InventoryEditorLayout.Rect h = headerRect(layout);
        return new InventoryEditorLayout.Rect(h.x(), h.y(), ICON_W, h.h());
    }

    /** The carriage every tile is stamped on: the roster's default when it has one, else its first. */
    static String tileCarriage(EditorRosterIndex index) {
        List<String> carriages = EditorStageDetailPane.carriagesOf(index);
        if (carriages.isEmpty()) return "";
        return carriages.contains(EditorStageDetailPane.DEFAULT_CARRIAGE)
            ? EditorStageDetailPane.DEFAULT_CARRIAGE : carriages.get(0);
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
        int headerSub = h.contains(mouseX, mouseY) ? MenuRowPainter.hitCell(header, mouseX, h.x() + ICON_W, h.right()) : -1;
        MenuRowPainter.drawRow(g, font, header, h.x() + ICON_W, h.y(), h.right(), ROW_H - 1, 0, headerSub >= 0, headerSub, null);
        drawToggle(g, font, layout, mouseX, mouseY);
        InventoryEditorLayout.Rect r = listRect(layout);
        List<EditorStagesPage.Row> rows = rows(index);
        EditorRosterPacket.StageEntry shown = EditorScreenState.effectiveStage(index);
        String shownId = shown == null ? "" : shown.id();
        if (EditorScreenState.stageGridView()) {
            renderTiles(g, font, layout, r, rows, index, shownId, mouseX, mouseY);
            return;
        }
        int visible = visibleRows(r);
        scroll = clamp(scroll, rows.size(), visible);
        int hoveredRow = rowAt(mouseX, mouseY, r, rows.size(), visible);
        for (int k = 0; k < visible && scroll + k < rows.size(); k++) {
            int idx = scroll + k;
            EditorStagesPage.Row row = rows.get(idx);
            int top = r.y() + k * ROW_H;
            boolean hov = idx == hoveredRow;
            int hoveredSub = hov ? MenuRowPainter.hitCell(row.entry(), mouseX, r.x() + ICON_W, r.right()) : -1;
            boolean chosen = shownId.equalsIgnoreCase(row.stageId());
            // The shown stage reads as chosen, not merely outlined: a tint under the row and the
            // browser's selection border around it.
            if (chosen) g.fill(r.x(), top, r.right(), top + ROW_H - 1, SELECTED_FILL);
            MenuRowPainter.drawRow(g, font, row.entry(), r.x() + ICON_W, top, r.right(), ROW_H - 1, idx, hov, hoveredSub, null);
            drawTopBlock(g, index.stage(row.stageId()), r.x() + 1, top + 1);
            if (chosen) g.renderOutline(r.x(), top, r.w(), ROW_H - 1, TemplateTilePainter.BORDER_SELECTED);
        }
        drawScrollbar(g, r, rows.size(), visible);
    }

    /** The view toggle cell: the glyph of the view it switches to. */
    private static void drawToggle(GuiGraphics g, Font font, InventoryEditorLayout layout, int mouseX, int mouseY) {
        InventoryEditorLayout.Rect t = toggleRect(layout);
        boolean hov = t.contains(mouseX, mouseY);
        g.fill(t.x(), t.y(), t.right() - 1, t.bottom() - 1, hov ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
        String glyph = EditorScreenState.stageGridView() ? LIST_GLYPH : GRID_GLYPH;
        g.drawString(font, glyph, t.x() + (t.w() - 1 - font.width(glyph)) / 2,
            t.y() + (t.h() - 1 - font.lineHeight) / 2 + 1, hov ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF, false);
    }

    /** The tile view: one tile per stage in the list's order, each the default carriage stamped with it. */
    private void renderTiles(GuiGraphics g, Font font, InventoryEditorLayout layout, InventoryEditorLayout.Rect r,
                             List<EditorStagesPage.Row> rows, EditorRosterIndex index, String shownId,
                             int mouseX, int mouseY) {
        grid = TemplateTileGridLayout.of(r.x(), r.y(), r.w(), r.h(), layout.tile(), TILE_GAP);
        tileScroll = grid.clampScroll(tileScroll, rows.size());
        String carriage = tileCarriage(index);
        int hoveredTile = tileAt(mouseX, mouseY, rows.size());
        g.enableScissor(r.x(), r.y(), r.right(), r.bottom());
        for (int i = 0; i < rows.size(); i++) {
            int x = grid.xFor(i);
            int y = grid.yFor(i, tileScroll);
            if (y + grid.tile() < r.y() || y > r.bottom()) continue;
            EditorStagesPage.Row row = rows.get(i);
            EditorRosterPacket.StageEntry stage = index.stage(row.stageId());
            drawTile(g, font, stage, carriage, x, y, grid.tile(),
                shownId.equalsIgnoreCase(row.stageId()), i == hoveredTile);
        }
        g.disableScissor();
        int content = grid.contentHeight(rows.size());
        if (content > r.h()) {
            int thumbH = Math.max(6, r.h() * r.h() / content);
            int thumbY = r.y() + (r.h() - thumbH) * tileScroll / Math.max(1, content - r.h());
            g.fill(r.right() - 2, r.y(), r.right(), r.bottom(), 0x40FFFFFF);
            g.fill(r.right() - 2, thumbY, r.right(), thumbY + thumbH, 0xC0FFEEBB);
        }
    }

    /** One stage's tile: its stamped carriage (asked for on first sight), its name, and the tile marks. */
    private static void drawTile(GuiGraphics g, Font font, EditorRosterPacket.StageEntry stage, String carriage,
                                 int x, int y, int size, boolean selected, boolean hovered) {
        g.fill(x, y, x + size, y + size, TemplateTilePainter.MODEL_BACKDROP);
        boolean drawn = false;
        if (stage != null && !carriage.isEmpty()) {
            StagePreviews.Key key = new StagePreviews.Key(stage.id(), carriage, TILE_SEED);
            StagePreviews.request(key);
            drawn = StagePreviews.draw(g, key, x + 1, y + 1, size - 2, size - 2, TILE_YAW, TemplateTilePainter.FILL);
        }
        String name = stage == null ? "" : stage.name();
        if (!drawn) {
            g.fill(x, y, x + size, y + size, TemplateTilePainter.SLATE);
            String initials = name.length() > 3 ? name.substring(0, 3) : name;
            g.drawString(font, initials, x + (size - font.width(initials)) / 2,
                y + (size - font.lineHeight) / 2, 0xFFB0B8C0, false);
        }
        if (!hovered && !selected) g.fill(x, y, x + size, y + size, TemplateTilePainter.IDLE_DIM);
        g.drawString(font, font.plainSubstrByWidth(name, size - 4), x + 2, y + size - font.lineHeight - 1,
            PreviewPane.CAPTION, true);
        if (selected) {
            g.renderOutline(x, y, size, size, TemplateTilePainter.BORDER_SELECTED);
            g.renderOutline(x + 1, y + 1, size - 2, size - 2, TemplateTilePainter.BORDER_SELECTED);
        } else {
            g.renderOutline(x, y, size, size, hovered ? TemplateTilePainter.BORDER_HOVER : TemplateTilePainter.BORDER_IDLE);
        }
    }

    /** The tile under the point, or -1. */
    private int tileAt(double mx, double my, int count) {
        if (grid == null) return -1;
        if (mx < grid.x() || mx >= grid.x() + grid.width() || my < grid.y() || my >= grid.y() + grid.height()) return -1;
        int col = (int) ((mx - grid.x()) / grid.stride());
        int row = (int) ((my - grid.y() + tileScroll) / grid.stride());
        if (col >= grid.columns() || mx - grid.x() - col * grid.stride() >= grid.tile()) return -1;
        if (my - grid.y() + tileScroll - row * grid.stride() >= grid.tile()) return -1;
        int i = row * grid.columns() + col;
        return i >= 0 && i < count ? i : -1;
    }

    /** The toggle's or a hovered tile's label, or null. */
    String tooltipAt(InventoryEditorLayout layout, EditorRosterIndex index, double mouseX, double mouseY) {
        if (toggleRect(layout).contains(mouseX, mouseY)) {
            return EditorScreenLang.text(EditorScreenState.stageGridView()
                ? EditorScreenLang.STAGES_VIEW_LIST : EditorScreenLang.STAGES_VIEW_GRID);
        }
        if (!EditorScreenState.stageGridView() || emptyNote(index) != null) return null;
        List<EditorStagesPage.Row> rows = rows(index);
        int i = tileAt(mouseX, mouseY, rows.size());
        if (i < 0) return null;
        EditorRosterPacket.StageEntry stage = index.stage(rows.get(i).stageId());
        return stage == null ? null : stage.name() + " · " + EditorStagesPage.levelText(stage);
    }

    /** The stage's most-used block as a small item icon — its usage list is ordered, so that is entry 0. */
    private static void drawTopBlock(GuiGraphics g, EditorRosterPacket.StageEntry stage, int x, int y) {
        if (stage == null || stage.blocks().isEmpty()) return;
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(ICON_SCALE, ICON_SCALE, 1f);
        g.renderItem(games.brennan.dungeontrain.client.menu.MenuBlockIcons.iconStackFor(stage.blocks().get(0).blockId()), 0, 0);
        g.pose().popPose();
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
        if (toggleRect(layout).contains(mouseX, mouseY)) {
            EditorScreenState.toggleStageGridView();
            return true;
        }
        InventoryEditorLayout.Rect h = headerRect(layout);
        if (h.contains(mouseX, mouseY)) {
            CommandMenuEntry header = header();
            int sub = MenuRowPainter.hitCell(header, (int) mouseX, h.x() + ICON_W, h.right());
            if (sub < 0) return false;
            CommandMenuEntry cell = MenuRowPainter.cellsOf(header)[sub];
            if (cell instanceof CommandMenuEntry.ClientAction action) action.action().run();
            return true;
        }
        InventoryEditorLayout.Rect r = listRect(layout);
        List<EditorStagesPage.Row> rows = rows(index);
        int idx = EditorScreenState.stageGridView() ? tileAt(mouseX, mouseY, rows.size())
            : rowAt(mouseX, mouseY, r, rows.size(), visibleRows(r));
        if (idx < 0) return false;
        EditorScreenState.selectStage(rows.get(idx).stageId());
        return true;
    }

    boolean over(InventoryEditorLayout layout, double mouseX, double mouseY) {
        return layout != null && rect(layout).contains(mouseX, mouseY);
    }

    /** Wheel: one row per notch (one tile row in the tile view), held within the list. */
    boolean scrollBy(int dir) {
        if (EditorScreenState.stageGridView()) {
            tileScroll = Math.max(0, tileScroll + dir * (grid == null ? ROW_H : grid.stride()));
            return true;
        }
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
