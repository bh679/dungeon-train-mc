package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.MenuBlockIcons;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.StageBlocksSyncPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The right pane on the Stages tab: the shown stage's name and gate, then every block its linked
 * parts use as a paged grid of item icons with usage counts.
 *
 * <p>Stands in for {@link EditorDetailPane} on that tab only. The template pane's icons, sheet,
 * settings rows and Test button mean nothing for a stage, so the whole column below the header is
 * the grid: an info line, the icons, and the pager slot the layout keeps at the bottom.</p>
 */
final class EditorStageDetailPane {

    static final int ROW_H = EditorDetailPane.ROW_H;
    /** An item icon is 16px; the cell gives it a pixel of air each side. */
    static final int CELL = 18;

    enum HitKind { NONE, BLOCK, PAGE_PREV, PAGE_NEXT }

    /** What the pointer is over; {@code index} is the block's index in the stage's list. */
    record Hit(HitKind kind, int index) {
        static final Hit NONE = new Hit(HitKind.NONE, -1);
    }

    private InventoryEditorLayout layout;
    private EditorRosterPacket.StageEntry stage;
    private IconGridPages pages = new IconGridPages(0, 1, 1);
    private int page;
    private String pagedStageId = "";
    private Hit hovered = Hit.NONE;

    /** The column below the header down to the Test row: info line + grid + pager slot. */
    static InventoryEditorLayout.Rect bodyOf(InventoryEditorLayout layout) {
        InventoryEditorLayout.Rect top = layout.icons();
        InventoryEditorLayout.Rect t = layout.test();
        return new InventoryEditorLayout.Rect(top.x(), top.y(), top.w(), Math.max(0, t.y() - 2 - top.y()));
    }

    /** The icons' rectangle: the body less the info line above and the pager slot below. */
    static InventoryEditorLayout.Rect gridRectOf(InventoryEditorLayout layout) {
        InventoryEditorLayout.Rect b = bodyOf(layout);
        return new InventoryEditorLayout.Rect(b.x(), b.y() + ROW_H, b.w(), Math.max(0, b.h() - 2 * ROW_H));
    }

    InventoryEditorLayout.Rect body() {
        return bodyOf(layout);
    }

    InventoryEditorLayout.Rect gridRect() {
        return gridRectOf(layout);
    }

    private InventoryEditorLayout.Rect pagerRect() {
        InventoryEditorLayout.Rect b = body();
        return new InventoryEditorLayout.Rect(b.x(), b.bottom() - ROW_H, b.w(), ROW_H);
    }

    /** The grid's shape at this layout: as many whole cells as fit, at least one each way. */
    static IconGridPages pagesFor(InventoryEditorLayout.Rect grid, int count) {
        return new IconGridPages(count, grid.w() / CELL, grid.h() / CELL);
    }

    /** Lay the pane out for {@code stage} (null when the roster lists none). Page resets when the stage changes. */
    void layout(InventoryEditorLayout layout, EditorRosterPacket.StageEntry stage) {
        this.layout = layout;
        this.stage = stage;
        String id = stage == null ? "" : stage.id();
        if (!id.equalsIgnoreCase(pagedStageId)) {
            pagedStageId = id;
            page = 0;
        }
        pages = pagesFor(gridRect(), stage == null ? 0 : stage.blocks().size());
        page = pages.clamp(page);
    }

    Hit hovered() {
        return hovered;
    }

    boolean over(double mx, double my) {
        return layout != null && body().contains(mx, my);
    }

    void render(GuiGraphics g, Font font, EditorScreenTheme theme, int mouseX, int mouseY) {
        hovered = hitTest(mouseX, mouseY);
        drawHeader(g, font, theme);
        if (stage == null) return;
        drawInfo(g, font);
        drawGrid(g, font);
        if (pages.hasPager()) {
            EditorPager.draw(g, font, pagerRect(), page, pages.pageCount(), switch (hovered.kind()) {
                case PAGE_PREV -> EditorPager.Hit.PREV;
                case PAGE_NEXT -> EditorPager.Hit.NEXT;
                default -> EditorPager.Hit.NONE;
            });
        }
    }

    private void drawHeader(GuiGraphics g, Font font, EditorScreenTheme theme) {
        InventoryEditorLayout.Rect h = layout.header();
        int ty = h.y() + (h.h() - font.lineHeight) / 2;
        String name = stage == null ? EditorScreenLang.text(EditorScreenLang.STAGES_NONE) : stage.name();
        g.drawString(font, name, h.x() + 2, ty, theme.panelText(), !theme.isLight());
    }

    /** {@code lvl 10..all · VUC · 4 parts}, and how many blocks the cap left out. */
    private void drawInfo(GuiGraphics g, Font font) {
        InventoryEditorLayout.Rect b = body();
        int ty = b.y() + (ROW_H - font.lineHeight) / 2;
        String info = EditorStagesPage.gateSummary(stage) + " · "
            + EditorScreenLang.text(EditorScreenLang.STAGES_PARTS, stage.partCount());
        int hidden = stage.totalUnique() - stage.blocks().size();
        if (hidden > 0) info += " · " + EditorScreenLang.text(EditorScreenLang.STAGES_MORE, hidden);
        g.drawString(font, info, b.x() + 2, ty, EditorDetailPane.DIM_TEXT, false);
    }

    private void drawGrid(GuiGraphics g, Font font) {
        InventoryEditorLayout.Rect r = gridRect();
        List<StageBlocksSyncPacket.BlockCount> blocks = stage.blocks();
        if (blocks.isEmpty()) {
            g.drawString(font, EditorScreenLang.text(EditorScreenLang.STAGES_NO_BLOCKS),
                r.x() + 2, r.y() + 2, EditorDetailPane.DIM_TEXT, false);
            return;
        }
        g.enableScissor(r.x(), r.y(), r.right(), r.bottom());
        for (int idx = pages.first(page); idx < pages.end(page); idx++) {
            int x = cellX(r, idx);
            int y = cellY(r, idx);
            if (hovered.kind() == HitKind.BLOCK && hovered.index() == idx) {
                g.fill(x, y, x + CELL, y + CELL, 0x40FFFFFF);
            }
            ItemStack stack = MenuBlockIcons.iconStackFor(blocks.get(idx).blockId());
            g.renderItem(stack, x + 1, y + 1);
            g.renderItemDecorations(font, stack, x + 1, y + 1, Integer.toString(blocks.get(idx).count()));
        }
        g.disableScissor();
    }

    private int cellX(InventoryEditorLayout.Rect r, int idx) {
        int slot = idx - pages.first(page);
        return r.x() + (slot % pages.cols()) * CELL;
    }

    private int cellY(InventoryEditorLayout.Rect r, int idx) {
        int slot = idx - pages.first(page);
        return r.y() + (slot / pages.cols()) * CELL;
    }

    Hit hitTest(double mx, double my) {
        if (layout == null || stage == null) return Hit.NONE;
        if (pages.hasPager()) {
            switch (EditorPager.hit(pagerRect(), page, pages.pageCount(), mx, my)) {
                case PREV -> { return new Hit(HitKind.PAGE_PREV, -1); }
                case NEXT -> { return new Hit(HitKind.PAGE_NEXT, -1); }
                case NONE -> { }
            }
        }
        InventoryEditorLayout.Rect r = gridRect();
        if (!r.contains(mx, my)) return Hit.NONE;
        int col = (int) ((mx - r.x()) / CELL);
        int row = (int) ((my - r.y()) / CELL);
        if (col >= pages.cols() || row >= pages.rows()) return Hit.NONE;
        int idx = pages.first(page) + row * pages.cols() + col;
        return idx < pages.end(page) ? new Hit(HitKind.BLOCK, idx) : Hit.NONE;
    }

    /** Wheel or arrow: one page per notch, held within the pages that exist. */
    boolean scrollBy(int dir) {
        int next = pages.clamp(page + dir);
        if (next == page) return false;
        page = next;
        return true;
    }

    /** The block under the pointer as {@code name · ×count}, or nothing. */
    List<String> tooltipAt(Hit hit) {
        if (hit.kind() != HitKind.BLOCK || stage == null) return List.of();
        if (hit.index() < 0 || hit.index() >= stage.blocks().size()) return List.of();
        StageBlocksSyncPacket.BlockCount b = stage.blocks().get(hit.index());
        String name = MenuBlockIcons.iconStackFor(b.blockId()).getHoverName().getString();
        return List.of(name, EditorScreenLang.text(EditorScreenLang.STAGES_BLOCK_TIP, b.count()));
    }
}
