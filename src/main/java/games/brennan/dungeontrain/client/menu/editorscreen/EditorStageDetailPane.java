package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.MenuBlockIcons;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.StageBlocksSyncPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Random;

/**
 * The right pane on the Stages tab: the shown stage's name, then its pages — first an overview
 * (the stage's icon row over a model of one linked template, with its gate rows under it), then
 * the blocks its linked parts use as a grid of item icons with usage counts, then the templates
 * and parts linked to it as rows a click selects.
 *
 * <p>Stands in for {@link EditorDetailPane} on that tab only. The overview keeps the template
 * pane's shape — icons, preview, rows — so a stage reads like a template does; the later pages use
 * the whole column below the header: an info line, the grid or the rows, and the pager slot the
 * layout keeps at the bottom.</p>
 */
final class EditorStageDetailPane {

    static final int ROW_H = EditorDetailPane.ROW_H;
    /** An item icon is 16px; the cell gives it a pixel of air each side. */
    static final int CELL = 18;

    enum HitKind { NONE, ICON, PREVIEW, SETTING, BLOCK, ROW, PAGE_PREV, PAGE_NEXT }

    /**
     * What the pointer is over; {@code index} is the icon's, setting row's, block's or linked row's
     * index in its list, and {@code sub} the cell of a setting row.
     */
    record Hit(HitKind kind, int index, int sub) {
        static final Hit NONE = new Hit(HitKind.NONE, -1, -1);

        Hit(HitKind kind, int index) {
            this(kind, index, -1);
        }
    }

    /** The overview is always the first page. */
    static final int OVERVIEW_PAGES = 1;

    /**
     * The pane's pages: the block grid's pages first, then the linked-template rows' pages. Pure,
     * so the split can be pinned. A stage with nothing linked still shows one (empty) template page,
     * so the pager always has somewhere to say so.
     */
    record Pages(IconGridPages blocks, int templateRows, int rowsPerPage) {
        Pages {
            rowsPerPage = Math.max(1, rowsPerPage);
        }

        int blockPages() {
            return blocks.pageCount();
        }

        int templatePages() {
            return Math.max(1, (templateRows + rowsPerPage - 1) / rowsPerPage);
        }

        int pageCount() {
            return OVERVIEW_PAGES + blockPages() + templatePages();
        }

        boolean hasPager() {
            return pageCount() > 1;
        }

        int clamp(int page) {
            return Math.max(0, Math.min(page, pageCount() - 1));
        }

        boolean isOverview(int page) {
            return clamp(page) < OVERVIEW_PAGES;
        }

        boolean isBlockPage(int page) {
            int p = clamp(page);
            return p >= OVERVIEW_PAGES && p < OVERVIEW_PAGES + blockPages();
        }

        /** The block grid's own page number for a block page. */
        int blockPage(int page) {
            return clamp(page) - OVERVIEW_PAGES;
        }

        /** The first template row on {@code page}, which must be a template page. */
        int firstRow(int page) {
            return (clamp(page) - OVERVIEW_PAGES - blockPages()) * rowsPerPage;
        }

        int endRow(int page) {
            return Math.min(templateRows, firstRow(page) + rowsPerPage);
        }
    }

    private InventoryEditorLayout layout;
    private EditorRosterPacket.StageEntry stage;
    private List<EditorStageTemplates.Row> templates = List.of();
    private Pages pages = new Pages(new IconGridPages(0, 1, 1), 0, 1);
    private int page;
    private String pagedStageId = "";
    private Hit hovered = Hit.NONE;

    /** The overview: its icons and their geometry, the model shown, and the gate rows. */
    private List<EditorScreenActions.Icon> icons = List.of();
    private int[] iconX = new int[0];
    private int iconCell = EditorDetailPane.ICON_CELL;
    private List<VariantKey> models = List.of();
    private int modelIdx;
    private VariantKey modelKey;
    private List<CommandMenuEntry> settings = List.of();
    private static final Random RESEED = new Random();

    /** The column below the header down to the Test row: info line + page + pager slot. */
    static InventoryEditorLayout.Rect bodyOf(InventoryEditorLayout layout) {
        InventoryEditorLayout.Rect top = layout.icons();
        InventoryEditorLayout.Rect t = layout.test();
        return new InventoryEditorLayout.Rect(top.x(), top.y(), top.w(), Math.max(0, t.y() - 2 - top.y()));
    }

    /** The page's rectangle: the body less the info line above and the pager slot below. */
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

    /**
     * Lay the pane out for {@code stage} (null when the roster lists none). Page and model reset
     * when the stage changes; {@code applyTo} is the template the Apply button would link.
     */
    void layout(InventoryEditorLayout layout, EditorRosterPacket.StageEntry stage, EditorRosterIndex index,
                VariantKey applyTo) {
        this.layout = layout;
        this.stage = stage;
        this.templates = EditorStageTemplates.rows(stage, index);
        String id = stage == null ? "" : stage.id();
        if (!id.equalsIgnoreCase(pagedStageId)) {
            pagedStageId = id;
            page = 0;
            modelIdx = 0;
        }
        InventoryEditorLayout.Rect r = gridRect();
        pages = new Pages(pagesFor(r, stage == null ? 0 : stage.blocks().size()), templates.size(), r.h() / ROW_H);
        page = pages.clamp(page);

        models = modelsFor(templates, index);
        modelIdx = models.isEmpty() ? 0 : Math.floorMod(modelIdx, models.size());
        modelKey = models.isEmpty() ? null : models.get(modelIdx);
        if (stage == null) {
            icons = List.of();
            settings = List.of();
        } else {
            icons = EditorStageActions.icons(stage, applyTo, models.size() > 1, this::reseed, this::stepModel);
            settings = EditorStageActions.settingRows(stage);
        }
        EditorDetailPane.IconRow row = EditorDetailPane.layoutIcons(icons.size(), layout.icons().x(), layout.icons().w());
        iconX = row.x();
        iconCell = row.cell();
    }

    /**
     * What the overview's model can show: every template and part linked to the stage, or — for a
     * stage nothing links to yet — the roster's first carriage, so the page is never a blank box.
     */
    static List<VariantKey> modelsFor(List<EditorStageTemplates.Row> linked, EditorRosterIndex index) {
        List<VariantKey> out = new java.util.ArrayList<>(linked.size());
        for (EditorStageTemplates.Row r : linked) {
            if (TemplateArt.of(r.key()) != null) out.add(r.key());
        }
        if (!out.isEmpty() || index == null) return out;
        for (EditorRosterPacket.Group g : index.groups()) {
            if (!"carriages".equals(g.categoryId()) || g.entries().isEmpty()) continue;
            out.add(VariantKey.of(g.entries().get(0).variant(), ""));
            break;
        }
        return out;
    }

    /** The template the overview's model shows, or null with nothing to show. */
    VariantKey modelKey() {
        return modelKey;
    }

    /** Previous / Next: the model steps through the linked templates, wrapping. */
    private void stepModel(int dir) {
        if (models.isEmpty()) return;
        modelIdx = Math.floorMod(modelIdx + dir, models.size());
        modelKey = models.get(modelIdx);
    }

    /** Refresh: drop the baked models and land on another linked template, so the box re-rolls. */
    private void reseed() {
        games.brennan.dungeontrain.client.builder.BuilderTilePreviews.clear();
        if (models.size() > 1) {
            int next = RESEED.nextInt(models.size() - 1);
            modelIdx = next >= modelIdx ? next + 1 : next;
            modelKey = models.get(modelIdx);
        }
    }

    List<EditorScreenActions.Icon> icons() {
        return icons;
    }

    List<CommandMenuEntry> settings() {
        return settings;
    }

    /** The overview's gate rows: from the sheet's top down to the pager slot. */
    private InventoryEditorLayout.Rect settingsRect() {
        InventoryEditorLayout.Rect b = body();
        int top = layout.sheet().y();
        return new InventoryEditorLayout.Rect(b.x(), top, b.w(), Math.max(0, b.bottom() - ROW_H - top));
    }

    Hit hovered() {
        return hovered;
    }

    boolean over(double mx, double my) {
        return layout != null && body().contains(mx, my);
    }

    /** The linked template a ROW hit names, or null. */
    VariantKey rowKey(Hit hit) {
        if (hit.kind() != HitKind.ROW || hit.index() < 0 || hit.index() >= templates.size()) return null;
        return templates.get(hit.index()).key();
    }

    void render(GuiGraphics g, Font font, EditorScreenTheme theme, float yaw, int mouseX, int mouseY) {
        hovered = hitTest(mouseX, mouseY);
        drawHeader(g, font, theme);
        if (stage == null) return;
        if (pages.isOverview(page)) {
            drawOverview(g, font, theme, yaw);
        } else {
            drawInfo(g, font);
            if (pages.isBlockPage(page)) drawGrid(g, font);
            else drawRows(g, font);
        }
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

    /** Icons over the model of one linked template, and the gate rows under it. */
    private void drawOverview(GuiGraphics g, Font font, EditorScreenTheme theme, float yaw) {
        drawIcons(g);
        TemplateArt art = TemplateArt.of(modelKey);
        String name = modelKey == null ? "" : modelKey.displayName();
        PreviewPane.draw(g, font, layout.preview(), art, name, yaw, theme, 0);
        InventoryEditorLayout.Rect r = settingsRect();
        int visible = r.h() / ROW_H;
        for (int i = 0; i < settings.size() && i < visible; i++) {
            int top = r.y() + i * ROW_H;
            boolean hov = hovered.kind() == HitKind.SETTING && hovered.index() == i;
            MenuRowPainter.drawRow(g, font, settings.get(i), r.x(), top, r.right(), ROW_H - 1, i, hov,
                hov ? hovered.sub() : -1, null);
        }
    }

    private void drawIcons(GuiGraphics g) {
        InventoryEditorLayout.Rect r = layout.icons();
        for (int i = 0; i < icons.size(); i++) {
            EditorScreenActions.Icon icon = icons.get(i);
            int x = iconX[i];
            boolean hov = hovered.kind() == HitKind.ICON && hovered.index() == i;
            boolean danger = EditorStageActions.DELETE.equals(icon.id());
            int fill = !icon.enabled() ? EditorDetailPane.DISABLED
                : hov ? (danger ? 0xC0FF5544 : MenuRowPainter.CELL_HOVER) : MenuRowPainter.CELL_IDLE;
            g.fill(x, r.y(), x + iconCell, r.y() + iconCell, fill);
            if (!icon.enabled()) tint(g, EditorDetailPane.DISABLED_ICON);
            else if (hov) tint(g, 0xFF000000);
            int sprite = Math.min(EditorDetailPane.ICON_SIZE, iconCell);
            g.blitSprite(EditorIcons.forAction(icon.id()), x + (iconCell - sprite) / 2,
                r.y() + (iconCell - sprite) / 2, sprite, sprite);
            g.setColor(1f, 1f, 1f, 1f);
        }
    }

    private static void tint(GuiGraphics g, int argb) {
        g.setColor(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
            (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
    }

    /**
     * On a block page: {@code lvl 10..all · VUC · 4 parts}, and how many blocks the cap left out.
     * On a template page: the gate and how many templates link to the stage.
     */
    private void drawInfo(GuiGraphics g, Font font) {
        InventoryEditorLayout.Rect b = body();
        int ty = b.y() + (ROW_H - font.lineHeight) / 2;
        String info = EditorStagesPage.gateSummary(stage) + " · ";
        if (pages.isBlockPage(page)) {
            info += EditorScreenLang.text(EditorScreenLang.STAGES_PARTS, stage.partCount());
            int hidden = stage.totalUnique() - stage.blocks().size();
            if (hidden > 0) info += " · " + EditorScreenLang.text(EditorScreenLang.STAGES_MORE, hidden);
        } else {
            info += EditorScreenLang.text(EditorScreenLang.STAGES_TEMPLATES, templates.size());
        }
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
        IconGridPages grid = pages.blocks();
        int bp = pages.blockPage(page);
        g.enableScissor(r.x(), r.y(), r.right(), r.bottom());
        for (int idx = grid.first(bp); idx < grid.end(bp); idx++) {
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

    private void drawRows(GuiGraphics g, Font font) {
        InventoryEditorLayout.Rect r = gridRect();
        if (templates.isEmpty()) {
            g.drawString(font, EditorScreenLang.text(EditorScreenLang.STAGES_NO_TEMPLATES),
                r.x() + 2, r.y() + 2, EditorDetailPane.DIM_TEXT, false);
            return;
        }
        g.enableScissor(r.x(), r.y(), r.right(), r.bottom());
        int first = pages.firstRow(page);
        for (int idx = first; idx < pages.endRow(page); idx++) {
            int top = r.y() + (idx - first) * ROW_H;
            boolean hov = hovered.kind() == HitKind.ROW && hovered.index() == idx;
            CommandMenuEntry entry = new CommandMenuEntry.Label(templates.get(idx).label());
            MenuRowPainter.drawRow(g, font, entry, r.x(), top, r.right(), ROW_H - 1, idx, hov, hov ? 0 : -1, null);
        }
        g.disableScissor();
    }

    private int cellX(InventoryEditorLayout.Rect r, int idx) {
        int slot = idx - pages.blocks().first(pages.blockPage(page));
        return r.x() + (slot % pages.blocks().cols()) * CELL;
    }

    private int cellY(InventoryEditorLayout.Rect r, int idx) {
        int slot = idx - pages.blocks().first(pages.blockPage(page));
        return r.y() + (slot / pages.blocks().cols()) * CELL;
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
        if (pages.isOverview(page)) {
            InventoryEditorLayout.Rect ir = layout.icons();
            if (my >= ir.y() && my < ir.y() + iconCell) {
                for (int i = 0; i < iconX.length; i++) {
                    if (mx >= iconX[i] && mx < iconX[i] + iconCell) return new Hit(HitKind.ICON, i);
                }
            }
            if (layout.preview().contains(mx, my)) return new Hit(HitKind.PREVIEW, 0);
            InventoryEditorLayout.Rect sr = settingsRect();
            if (sr.contains(mx, my)) {
                int i = (int) ((my - sr.y()) / ROW_H);
                if (i < 0 || i >= settings.size()) return Hit.NONE;
                int sub = MenuRowPainter.hitCell(settings.get(i), (int) mx, sr.x(), sr.right());
                return sub < 0 ? Hit.NONE : new Hit(HitKind.SETTING, i, sub);
            }
            return Hit.NONE;
        }
        InventoryEditorLayout.Rect r = gridRect();
        if (!r.contains(mx, my)) return Hit.NONE;
        if (pages.isBlockPage(page)) {
            IconGridPages grid = pages.blocks();
            int bp = pages.blockPage(page);
            int col = (int) ((mx - r.x()) / CELL);
            int row = (int) ((my - r.y()) / CELL);
            if (col >= grid.cols() || row >= grid.rows()) return Hit.NONE;
            int idx = grid.first(bp) + row * grid.cols() + col;
            return idx < grid.end(bp) ? new Hit(HitKind.BLOCK, idx) : Hit.NONE;
        }
        int idx = pages.firstRow(page) + (int) ((my - r.y()) / ROW_H);
        return idx < pages.endRow(page) ? new Hit(HitKind.ROW, idx) : Hit.NONE;
    }

    /** Wheel or arrow: one page per notch, held within the pages that exist. */
    boolean scrollBy(int dir) {
        int next = pages.clamp(page + dir);
        if (next == page) return false;
        page = next;
        return true;
    }

    /** An icon's label (and why it is off, or what it acts on), or the block under the pointer as {@code name · ×count}. */
    List<String> tooltipAt(Hit hit) {
        if (stage == null) return List.of();
        if (hit.kind() == HitKind.ICON) {
            if (hit.index() < 0 || hit.index() >= icons.size()) return List.of();
            EditorScreenActions.Icon icon = icons.get(hit.index());
            String label = EditorScreenLang.text(icon.labelKey());
            if (!icon.enabled()) {
                return icon.disabledKey() == null ? List.of(label) : List.of(label, EditorScreenLang.text(icon.disabledKey()));
            }
            return icon.detail() == null ? List.of(label) : List.of(label, icon.detail());
        }
        if (hit.kind() != HitKind.BLOCK) return List.of();
        if (hit.index() < 0 || hit.index() >= stage.blocks().size()) return List.of();
        StageBlocksSyncPacket.BlockCount b = stage.blocks().get(hit.index());
        String name = MenuBlockIcons.iconStackFor(b.blockId()).getHoverName().getString();
        return List.of(name, EditorScreenLang.text(EditorScreenLang.STAGES_BLOCK_TIP, b.count()));
    }
}
