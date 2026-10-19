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
 *
 * <p>A family of stages (the wood stages) is one row or tile; clicking it shows its current member
 * and drops a list of the family under it to pick another. The dropdown is drawn last by the screen
 * ({@link #renderDropdown}) so it sits over the detail pane, and takes the next click wherever it lands.</p>
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
    private int scroll;
    /** The carriage and roll the overview shows: every tile follows them, so the tiles compare stages, not rolls. */
    private String tileCarriage = "";
    private long tileSeed;
    /** Per stage, the last model its tile managed to draw — shown faded while the next one is baking. */
    private final java.util.Map<String, StagePreviews.Key> lastDrawn = new java.util.HashMap<>();
    /** Over a tile still showing its previous model. */
    static final int LOADING_FADE = 0x80000000;
    /** The tile view's scroll, in pixels. */
    private int tileScroll;
    private TemplateTileGridLayout grid;
    /** The family whose dropdown is open, or null. */
    private String openGroup;
    /** Where the open family's row or tile was drawn this frame, and its members — null while it is out of sight. */
    private InventoryEditorLayout.Rect dropdownAnchor;
    private List<String> dropdownMembers = List.of();
    /** The dropdown's own rectangle, its first shown member and how many fit, as last drawn. */
    private InventoryEditorLayout.Rect dropdownRect;
    private int dropdownScroll;
    private int dropdownVisible;
    /** Wider than a row's name cell needs, so a member's name and band both fit. */
    static final int DROPDOWN_MIN_W = 120;
    static final int DROPDOWN_FILL = 0xF0101418;
    static final int DROPDOWN_BORDER = 0xFFB0B8C0;

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

    /** The list with each family folded; a family row's click opens (or shuts) its dropdown. */
    List<EditorStagesPage.Row> rows(EditorRosterIndex index) {
        return EditorStagesPage.groupedRows(index.stages(), EditorScreenState.stageSort(), EditorScreenState::selectStage,
            group -> EditorScreenState.stageGroupPick(index, group), this::toggleGroup);
    }

    /** Opens the family's dropdown, or shuts it when it is the one open. */
    void toggleGroup(String group) {
        openGroup = group != null && group.equals(openGroup) ? null : group;
        dropdownScroll = 0;
    }

    boolean dropdownOpen() {
        return openGroup != null && dropdownAnchor != null;
    }

    void closeDropdown() {
        openGroup = null;
    }

    static CommandMenuEntry header() {
        return EditorStagesPage.header(EditorScreenState.stageSort(), EditorScreenState::sortStages);
    }

    /** The view toggle: the titles row's first column, above the block icons. */
    static InventoryEditorLayout.Rect toggleRect(InventoryEditorLayout layout) {
        InventoryEditorLayout.Rect h = headerRect(layout);
        return new InventoryEditorLayout.Rect(h.x(), h.y(), ICON_W, h.h());
    }

    /** What the overview is showing, so the tiles show the same carriage at the same roll. */
    void followModel(String carriage, long seed) {
        this.tileCarriage = carriage == null ? "" : carriage;
        this.tileSeed = seed;
    }

    void render(GuiGraphics g, Font font, EditorScreenTheme theme, InventoryEditorLayout layout,
                EditorRosterIndex index, int mouseX, int mouseY) {
        InventoryEditorLayout.Rect whole = rect(layout);
        g.fill(whole.x() - 1, whole.y() - 1, whole.right() + 1, whole.bottom() + 1, theme.subPanel());
        // Found again below if the open family's row is still in sight; else the dropdown shuts.
        dropdownAnchor = null;
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
            if (dropdownAnchor == null) openGroup = null;
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
            boolean chosen = row.stands(shownId);
            if (row.isGroup() && row.group().equals(openGroup)) anchorAt(new InventoryEditorLayout.Rect(r.x(), top, r.w(), ROW_H - 1), row);
            // The shown stage reads as chosen, not merely outlined: a tint under the row and the
            // browser's selection border around it.
            if (chosen) g.fill(r.x(), top, r.right(), top + ROW_H - 1, SELECTED_FILL);
            MenuRowPainter.drawRow(g, font, row.entry(), r.x() + ICON_W, top, r.right(), ROW_H - 1, idx, hov, hoveredSub, null);
            drawTopBlock(g, index.stage(row.stageId()), r.x() + 1, top + 1);
            if (chosen) g.renderOutline(r.x(), top, r.w(), ROW_H - 1, TemplateTilePainter.BORDER_SELECTED);
        }
        drawScrollbar(g, r, rows.size(), visible);
        if (dropdownAnchor == null) openGroup = null;
    }

    private void anchorAt(InventoryEditorLayout.Rect at, EditorStagesPage.Row row) {
        dropdownAnchor = at;
        dropdownMembers = row.memberIds();
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
        String carriage = tileCarriage;
        int hoveredTile = tileAt(mouseX, mouseY, rows.size());
        g.enableScissor(r.x(), r.y(), r.right(), r.bottom());
        for (int i = 0; i < rows.size(); i++) {
            int x = grid.xFor(i);
            int y = grid.yFor(i, tileScroll);
            if (y + grid.tile() < r.y() || y > r.bottom()) continue;
            EditorStagesPage.Row row = rows.get(i);
            EditorRosterPacket.StageEntry stage = index.stage(row.stageId());
            if (row.isGroup() && row.group().equals(openGroup)) anchorAt(new InventoryEditorLayout.Rect(x, y, grid.tile(), grid.tile()), row);
            drawTile(g, font, stage, caption(row, stage), carriage, tileSeed, x, y, grid.tile(),
                row.stands(shownId), i == hoveredTile, lastDrawn);
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
    private static void drawTile(GuiGraphics g, Font font, EditorRosterPacket.StageEntry stage, String caption, String carriage,
                                 long seed, int x, int y, int size, boolean selected, boolean hovered,
                                 java.util.Map<String, StagePreviews.Key> lastDrawn) {
        g.fill(x, y, x + size, y + size, TemplateTilePainter.MODEL_BACKDROP);
        boolean drawn = false;
        if (stage != null && !carriage.isEmpty()) {
            StagePreviews.Key key = new StagePreviews.Key(stage.id(), carriage, seed);
            StagePreviews.request(key);
            drawn = StagePreviews.draw(g, key, x + 1, y + 1, size - 2, size - 2, TILE_YAW, TemplateTilePainter.FILL);
            if (drawn) {
                lastDrawn.put(stage.id(), key);
            } else {
                // The model it showed last, faded, until the new one is baked: the tile keeps its
                // place in the eye instead of blinking to a slate.
                StagePreviews.Key last = lastDrawn.get(stage.id());
                if (last != null && StagePreviews.draw(g, last, x + 1, y + 1, size - 2, size - 2, TILE_YAW, TemplateTilePainter.FILL)) {
                    g.fill(x + 1, y + 1, x + size - 1, y + size - 1, LOADING_FADE);
                    drawn = true;
                }
            }
        }
        String name = caption;
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

    /** A tile's caption: the stage's name, or for a family {@code Wood: spruce ▾}. */
    private static String caption(EditorStagesPage.Row row, EditorRosterPacket.StageEntry stage) {
        String name = stage == null ? "" : stage.name();
        return row.isGroup() ? EditorStagesPage.groupLabel(row.group(), name) : name;
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
        EditorStagesPage.Row row = rows.get(i);
        EditorRosterPacket.StageEntry stage = index.stage(row.stageId());
        return stage == null ? null : caption(row, stage) + " · " + EditorStagesPage.levelText(stage);
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
        EditorStagesPage.Row row = rows.get(idx);
        EditorScreenState.selectStage(row.stageId());
        if (row.isGroup()) toggleGroup(row.group());
        return true;
    }

    /**
     * The open family's members under its row or tile (above it when there is more room there),
     * each {@code icon · name · band}, the shown one tinted. Drawn after every pane so it covers them.
     */
    void renderDropdown(GuiGraphics g, Font font, EditorRosterIndex index, int screenW, int screenH,
                        int mouseX, int mouseY) {
        dropdownRect = null;
        if (!dropdownOpen() || index == null || dropdownMembers.isEmpty()) return;
        int widest = 0;
        for (String id : dropdownMembers) {
            EditorRosterPacket.StageEntry m = index.stage(id);
            if (m != null) widest = Math.max(widest, font.width(m.name()) + font.width(EditorStagesPage.levelText(m)));
        }
        int w = Math.min(screenW - 4, Math.max(Math.max(DROPDOWN_MIN_W, dropdownAnchor.w()), ICON_W + widest + 16));
        int x = Math.max(2, Math.min(dropdownAnchor.x(), screenW - 2 - w));
        int below = screenH - 2 - dropdownAnchor.bottom();
        int above = dropdownAnchor.y() - 2;
        int n = dropdownMembers.size();
        boolean down = below >= Math.min(n, 4) * ROW_H || below >= above;
        dropdownVisible = Math.max(1, Math.min(n, (down ? below : above) / ROW_H));
        dropdownScroll = clamp(dropdownScroll, n, dropdownVisible);
        int h = dropdownVisible * ROW_H;
        int y = down ? dropdownAnchor.bottom() : dropdownAnchor.y() - h;
        dropdownRect = new InventoryEditorLayout.Rect(x, y, w, h);
        g.pose().pushPose();
        // Above the item icons and tile models of the panes underneath.
        g.pose().translate(0, 0, 400);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, DROPDOWN_BORDER);
        g.fill(x, y, x + w, y + h, DROPDOWN_FILL);
        String shown = EditorScreenState.selectedStageId();
        int hovered = dropdownAt(mouseX, mouseY);
        for (int k = 0; k < dropdownVisible && dropdownScroll + k < n; k++) {
            int idx = dropdownScroll + k;
            EditorRosterPacket.StageEntry m = index.stage(dropdownMembers.get(idx));
            if (m == null) continue;
            int top = y + k * ROW_H;
            if (m.id().equalsIgnoreCase(shown)) g.fill(x, top, x + w, top + ROW_H - 1, SELECTED_FILL);
            if (idx == hovered) g.fill(x, top, x + w, top + ROW_H - 1, MenuRowPainter.CELL_HOVER);
            drawTopBlock(g, m, x + 1, top + 1);
            int text = idx == hovered ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF;
            int ty = top + (ROW_H - 1 - font.lineHeight) / 2 + 1;
            g.drawString(font, m.name(), x + ICON_W + 3, ty, text, false);
            String level = EditorStagesPage.levelText(m);
            g.drawString(font, level, x + w - 4 - font.width(level), ty, 0xFFB0B8C0, false);
        }
        drawScrollbar(g, dropdownRect, n, dropdownVisible, dropdownScroll);
        g.pose().popPose();
    }

    /** The dropdown member under the point, or -1. */
    private int dropdownAt(double mx, double my) {
        if (dropdownRect == null || !dropdownRect.contains(mx, my)) return -1;
        int idx = dropdownScroll + (int) ((my - dropdownRect.y()) / ROW_H);
        return idx < dropdownMembers.size() ? idx : -1;
    }

    /**
     * A click while the dropdown is open: on a member it picks that member; anywhere else it only
     * shuts the dropdown (a click on the family's own row shuts it too, as a second press should).
     */
    void dropdownClicked(double mouseX, double mouseY) {
        int idx = dropdownAt(mouseX, mouseY);
        if (idx >= 0) EditorScreenState.pickStageInGroup(openGroup, dropdownMembers.get(idx));
        closeDropdown();
    }

    boolean overDropdown(double mouseX, double mouseY) {
        return dropdownRect != null && dropdownRect.contains(mouseX, mouseY);
    }

    /** Wheel over the dropdown: one member per notch. */
    void scrollDropdown(int dir) {
        dropdownScroll = clamp(dropdownScroll + dir, dropdownMembers.size(), dropdownVisible);
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
        drawScrollbar(g, r, count, visible, scroll);
    }

    private static void drawScrollbar(GuiGraphics g, InventoryEditorLayout.Rect r, int count, int visible, int scroll) {
        if (count <= visible || visible <= 0) return;
        int thumbH = Math.max(6, r.h() * visible / count);
        int thumbY = r.y() + (r.h() - thumbH) * scroll / Math.max(1, count - visible);
        g.fill(r.right() - 2, r.y(), r.right(), r.bottom(), 0x40FFFFFF);
        g.fill(r.right() - 2, thumbY, r.right(), thumbY + thumbH, 0xC0FFEEBB);
    }
}
