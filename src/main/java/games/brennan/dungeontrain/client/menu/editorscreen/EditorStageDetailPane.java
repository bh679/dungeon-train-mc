package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.builder.StagePreviews;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.MenuBlockIcons;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.client.render.StagePlaceholderItemRenderer;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.StageBlocksSyncPacket;
import games.brennan.dungeontrain.net.StagePaletteEditPacket;
import games.brennan.dungeontrain.net.StagePaletteSyncPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The right pane on the Stages tab: the shown stage's name, then its pages — an overview (the
 * stage's icon row over a model of one carriage stamped with it, and its gate sheet), the
 * placeholder palette (Solid, Shapes, Wood), the stone palette, the blocks its linked parts use as
 * a grid of item icons with usage counts, and the templates and parts linked to it as rows a click
 * selects.
 *
 * <p>Stands in for {@link EditorDetailPane} on that tab only. The overview keeps the template
 * pane's shape — icons, preview, sheet — so a stage reads like a template does; the later pages use
 * the whole column below the header: an info line, the page's rows, and the pager slot the layout
 * keeps at the bottom.</p>
 */
final class EditorStageDetailPane {

    static final int ROW_H = EditorDetailPane.ROW_H;
    /** An item icon is 16px; the cell gives it a pixel of air each side. */
    static final int CELL = 18;
    /** The stone page's kind labels take this much of a labelled row before its cells. */
    static final int LABEL_W = 44;
    /** A small square in a cell's corner marking a user override. */
    static final int OVERRIDE_DOT = 0xFFFFCC33;

    enum HitKind { NONE, ICON, PREVIEW, SHEET, CELL, FAMILY, BLOCK, ROW, PAGE_PREV, PAGE_NEXT }

    /**
     * What the pointer is over; {@code index} is the icon's, placed sheet cell's, block's, palette
     * row's or linked row's index in its list, and {@code sub} the cell within a palette row.
     */
    record Hit(HitKind kind, int index, int sub) {
        static final Hit NONE = new Hit(HitKind.NONE, -1, -1);

        Hit(HitKind kind, int index) {
            this(kind, index, -1);
        }
    }

    /** The pane's sections, in page order. */
    enum SectionKind { OVERVIEW, PALETTE, STONE, BLOCKS, TEMPLATES }

    /** One section: how many rows it has and how many a page of it holds. */
    record Section(SectionKind kind, int rows, int perPage) {
        Section {
            perPage = Math.max(1, perPage);
        }

        /** A section always has a page, even with nothing in it, so the pager can say so. */
        int pages() {
            return Math.max(1, (rows + perPage - 1) / perPage);
        }
    }

    /**
     * The pane's pages as its sections laid end to end. Pure, so the split can be pinned: which
     * section a page belongs to, and which of that section's rows it shows.
     */
    record Pages(List<Section> sections) {
        Pages {
            sections = List.copyOf(sections);
        }

        int pageCount() {
            int n = 0;
            for (Section s : sections) n += s.pages();
            return n;
        }

        boolean hasPager() {
            return pageCount() > 1;
        }

        int clamp(int page) {
            return Math.max(0, Math.min(page, pageCount() - 1));
        }

        /** The section {@code page} falls in. */
        Section sectionOf(int page) {
            int p = clamp(page);
            for (Section s : sections) {
                if (p < s.pages()) return s;
                p -= s.pages();
            }
            return sections.get(sections.size() - 1);
        }

        SectionKind kindOf(int page) {
            return sectionOf(page).kind();
        }

        /** The first row of its section shown on {@code page}. */
        int firstRow(int page) {
            int p = clamp(page);
            for (Section s : sections) {
                if (p < s.pages()) return p * s.perPage();
                p -= s.pages();
            }
            return 0;
        }

        /** One past the last row of its section shown on {@code page}. */
        int endRow(int page) {
            Section s = sectionOf(page);
            return Math.min(s.rows(), firstRow(page) + s.perPage());
        }

        /** The first page of a section kind, or 0 when the pane has none. */
        int firstPageOf(SectionKind kind) {
            int p = 0;
            for (Section s : sections) {
                if (s.kind() == kind) return p;
                p += s.pages();
            }
            return 0;
        }
    }

    private InventoryEditorLayout layout;
    private EditorRosterPacket.StageEntry stage;
    private List<EditorStageTemplates.Row> templates = List.of();
    private List<EditorStagePalettePage.Row> paletteRows = List.of();
    private List<EditorStagePalettePage.Row> stoneRows = List.of();
    private int blockCols = 1;
    private Pages pages = new Pages(List.of(new Section(SectionKind.OVERVIEW, 1, 1)));
    private int page;
    private Hit hovered = Hit.NONE;

    /** The overview: its icons and their geometry, the carriage shown at which roll, and the sheet. */
    private List<EditorScreenActions.Icon> icons = List.of();
    private int[] iconX = new int[0];
    private int iconCell = EditorDetailPane.ICON_CELL;
    private List<String> carriages = List.of();
    private int carriageIdx = -1;
    private long seed;
    private StagePreviews.Key modelKey;
    /** The last model the box managed to draw — shown faded while the next roll or carriage bakes. */
    private StagePreviews.Key lastDrawnModel;
    private List<TemplateDataSheet.Line> sheetLines = List.of();
    private List<TemplateDataSheet.Placed> sheetCells = List.of();
    private static final Random RESEED = new Random();
    /** The carriage the overview opens on, when the roster has it. */
    static final String DEFAULT_CARRIAGE = "standard";

    /**
     * The whole column from the header's top down to the Test row: page + pager slot. The pages
     * after the overview draw no header — the list beside them already names the stage — so they
     * take its row too.
     */
    static InventoryEditorLayout.Rect bodyOf(InventoryEditorLayout layout) {
        InventoryEditorLayout.Rect top = layout.header();
        InventoryEditorLayout.Rect t = layout.test();
        return new InventoryEditorLayout.Rect(top.x(), top.y(), top.w(), Math.max(0, t.y() - 2 - top.y()));
    }

    /** The page's rectangle: the body less the pager slot below. */
    static InventoryEditorLayout.Rect gridRectOf(InventoryEditorLayout layout) {
        InventoryEditorLayout.Rect b = bodyOf(layout);
        return new InventoryEditorLayout.Rect(b.x(), b.y(), b.w(), Math.max(0, b.h() - ROW_H));
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
     * Lay the pane out for {@code stage} (null when the roster lists none). The page returns to the
     * overview when the stage changes; {@code applyTo} is the template the Apply button would link.
     */
    void layout(InventoryEditorLayout layout, EditorRosterPacket.StageEntry stage, EditorRosterIndex index,
                VariantKey applyTo) {
        this.layout = layout;
        this.stage = stage;
        this.templates = EditorStageTemplates.rows(stage, index);
        // The page, the carriage and its roll are all the author's choice and outlive the stage
        // they were picked on, so two stages can be compared on the same page and model.
        if (carriageIdx < 0) seed = RESEED.nextLong();

        InventoryEditorLayout.Rect r = gridRect();
        IconGridPages grid = pagesFor(r, stage == null ? 0 : stage.blocks().size());
        blockCols = grid.cols();
        int cellRows = Math.max(1, r.h() / CELL);
        EditorRosterPacket.Palette palette = stage == null ? EditorRosterPacket.Palette.NONE : stage.palette();
        paletteRows = stage == null ? List.of() : EditorStagePalettePage.paletteRows(palette, blockCols);
        stoneRows = stage == null ? List.of() : EditorStagePalettePage.stoneRows(palette);
        int blockRows = stage == null ? 0 : (stage.blocks().size() + blockCols - 1) / blockCols;
        pages = new Pages(List.of(
            new Section(SectionKind.OVERVIEW, 1, 1),
            new Section(SectionKind.PALETTE, paletteRows.size(), cellRows),
            new Section(SectionKind.STONE, stoneRows.size(), cellRows),
            new Section(SectionKind.BLOCKS, blockRows, grid.rows()),
            new Section(SectionKind.TEMPLATES, templates.size(), r.h() / ROW_H)));
        page = pages.clamp(page);

        carriages = carriagesOf(index);
        if (carriageIdx < 0) carriageIdx = Math.max(0, carriages.indexOf(DEFAULT_CARRIAGE));
        carriageIdx = carriages.isEmpty() ? 0 : Math.floorMod(carriageIdx, carriages.size());
        modelKey = stage == null || carriages.isEmpty() ? null
            : new StagePreviews.Key(stage.id(), carriages.get(carriageIdx), seed);
        if (stage == null) {
            icons = List.of();
            sheetLines = List.of();
        } else {
            icons = EditorStageActions.icons(stage, applyTo, carriages.size() > 1, this::reseed, this::stepCarriage);
            sheetLines = EditorStageActions.sheetLines(stage, templates.size(),
                games.brennan.dungeontrain.client.EditorStatusHudOverlay.isDevModeOn());
        }
        EditorDetailPane.IconRow row = EditorDetailPane.layoutIcons(icons.size(), layout.icons().x(), layout.icons().w());
        iconX = row.x();
        iconCell = row.cell();
    }

    /** Every carriage the roster lists, in its order — what the overview's model pages through. */
    static List<String> carriagesOf(EditorRosterIndex index) {
        List<String> out = new ArrayList<>();
        if (index == null) return out;
        for (EditorRosterPacket.Group g : index.groups()) {
            if (!"carriages".equals(g.categoryId())) continue;
            for (EditorRosterPacket.Entry e : g.entries()) out.add(e.variant().modelId());
        }
        return out;
    }

    /** The stage-stamped carriage the overview shows, or null with nothing to show. */
    StagePreviews.Key modelKey() {
        return modelKey;
    }

    /** The carriage the overview shows ("" before the roster), and its roll — what the list's tiles follow. */
    String carriageShown() {
        return carriages.isEmpty() ? "" : carriages.get(carriageIdx);
    }

    long seedShown() {
        return seed;
    }

    /** Previous / Next: the model steps through the roster's carriages, wrapping. */
    private void stepCarriage(int dir) {
        if (carriages.isEmpty()) return;
        carriageIdx = Math.floorMod(carriageIdx + dir, carriages.size());
    }

    /** Refresh: a new seed, so the parts' and shell's block variants roll again. */
    private void reseed() {
        seed = RESEED.nextLong();
    }

    List<EditorScreenActions.Icon> icons() {
        return icons;
    }

    /** The sheet cell a SHEET hit names, or null. */
    TemplateDataSheet.Placed sheetCell(int index) {
        return index >= 0 && index < sheetCells.size() ? sheetCells.get(index) : null;
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

    /** The palette rows the current page draws from, by its section. */
    private List<EditorStagePalettePage.Row> paletteRowsShown() {
        return pages.kindOf(page) == SectionKind.STONE ? stoneRows : paletteRows;
    }

    /** The placeholder name a CELL hit names, or null. */
    String cellName(Hit hit) {
        if (hit.kind() != HitKind.CELL) return null;
        List<EditorStagePalettePage.Row> rows = paletteRowsShown();
        if (hit.index() < 0 || hit.index() >= rows.size()) return null;
        List<String> names = rows.get(hit.index()).names();
        return hit.sub() >= 0 && hit.sub() < names.size() ? names.get(hit.sub()) : null;
    }

    /** The edit a CELL or FAMILY hit sends, or null — the server reads the held block. */
    StagePaletteEditPacket editFor(Hit hit) {
        if (stage == null) return null;
        if (hit.kind() == HitKind.CELL) {
            String name = cellName(hit);
            return name == null ? null
                : new StagePaletteEditPacket(StagePaletteEditPacket.Op.SET_OVERRIDE, stage.id(), name, true);
        }
        if (hit.kind() == HitKind.FAMILY) {
            List<EditorStagePalettePage.Row> rows = paletteRowsShown();
            if (hit.index() < 0 || hit.index() >= rows.size() || rows.get(hit.index()).familyOp() == null) return null;
            return new StagePaletteEditPacket(rows.get(hit.index()).familyOp(), stage.id(), "", true);
        }
        return null;
    }

    void render(GuiGraphics g, Font font, EditorScreenTheme theme, float yaw, int mouseX, int mouseY) {
        hovered = hitTest(mouseX, mouseY);
        if (stage == null) {
            drawHeader(g, font, theme);
            return;
        }
        switch (pages.kindOf(page)) {
            case OVERVIEW -> {
                drawHeader(g, font, theme);
                drawOverview(g, font, theme, yaw);
            }
            case PALETTE, STONE -> drawPalette(g, font, paletteRowsShown());
            case BLOCKS -> drawGrid(g, font);
            case TEMPLATES -> drawRows(g, font);
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

    /** Icons over the model of one carriage stamped with the stage, and the gate sheet under it. */
    private void drawOverview(GuiGraphics g, Font font, EditorScreenTheme theme, float yaw) {
        drawIcons(g);
        drawModel(g, font, theme, yaw);
        sheetCells = TemplateDataSheet.place(sheetLines, layout.sheet(), font);
        TemplateDataSheet.draw(g, font, layout.sheet(), sheetLines, sheetCells,
            hovered.kind() == HitKind.SHEET ? hovered.index() : -1);
    }

    /** The preview box: the stamped carriage once it has arrived, its name and "…" until then. */
    private void drawModel(GuiGraphics g, Font font, EditorScreenTheme theme, float yaw) {
        InventoryEditorLayout.Rect r = layout.preview();
        g.fill(r.x(), r.y(), r.right(), r.bottom(), PreviewPane.BACKDROP);
        boolean drawn = false;
        if (modelKey != null) {
            StagePreviews.request(modelKey);
            drawn = StagePreviews.draw(g, modelKey, r.x(), r.y(), r.w(), r.h(), yaw, PreviewPane.FILL);
            if (drawn) {
                lastDrawnModel = modelKey;
            } else if (lastDrawnModel != null
                && StagePreviews.draw(g, lastDrawnModel, r.x(), r.y(), r.w(), r.h(), yaw, PreviewPane.FILL)) {
                g.fill(r.x(), r.y(), r.right(), r.bottom(), EditorStagesPane.LOADING_FADE);
                drawn = true;
            }
        }
        if (!drawn) {
            String pending = EditorScreenLang.text(modelKey == null
                ? EditorScreenLang.NOTHING_SELECTED : EditorScreenLang.SHEET_PENDING);
            g.drawString(font, pending, r.x() + (r.w() - font.width(pending)) / 2,
                r.y() + (r.h() - font.lineHeight) / 2, PreviewPane.HINT, false);
        }
        if (modelKey != null) {
            g.drawString(font, font.plainSubstrByWidth(modelKey.carriageId(), r.w() - 6), r.x() + 3, r.y() + 2,
                PreviewPane.CAPTION, true);
        }
        g.renderOutline(r.x(), r.y(), r.w(), r.h(), theme.outline());
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

    /** The palette page's rows: headings as text, cells as the placeholder icons resolved for this stage. */
    private void drawPalette(GuiGraphics g, Font font, List<EditorStagePalettePage.Row> rows) {
        InventoryEditorLayout.Rect r = gridRect();
        g.enableScissor(r.x(), r.y(), r.right(), r.bottom());
        int first = pages.firstRow(page);
        for (int idx = first; idx < pages.endRow(page); idx++) {
            EditorStagePalettePage.Row row = rows.get(idx);
            int top = r.y() + (idx - first) * CELL;
            if (row.isHeading()) {
                boolean hov = hovered.kind() == HitKind.FAMILY && hovered.index() == idx;
                if (hov) g.fill(r.x(), top, r.right(), top + CELL - 1, MenuRowPainter.CELL_HOVER);
                int color = hov ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF;
                g.drawString(font, row.text(), r.x() + 2, top + (CELL - font.lineHeight) / 2, color, false);
                continue;
            }
            int x0 = cellsLeft(r, row);
            if (row.kind() == EditorStagePalettePage.Kind.LABELLED) {
                g.drawString(font, font.plainSubstrByWidth(row.text(), LABEL_W - 4), r.x() + 2,
                    top + (CELL - font.lineHeight) / 2, EditorDetailPane.DIM_TEXT, false);
            }
            for (int c = 0; c < row.names().size(); c++) {
                int x = x0 + c * CELL;
                boolean hov = hovered.kind() == HitKind.CELL && hovered.index() == idx && hovered.sub() == c;
                if (hov) g.fill(x, top, x + CELL, top + CELL, 0x40FFFFFF);
                drawPlaceholder(g, row.names().get(c), x + 1, top + 1);
            }
        }
        g.disableScissor();
    }

    /** Where a row's cells start: after the label column on a labelled row. */
    private int cellsLeft(InventoryEditorLayout.Rect r, EditorStagePalettePage.Row row) {
        return row.kind() == EditorStagePalettePage.Kind.LABELLED ? r.x() + LABEL_W : r.x();
    }

    /**
     * One placeholder cell: the block it resolves to for this stage with the placeholder's own tile
     * ghosted over it — the item's own renderer, told the answer — and a dot when it is an override.
     */
    private void drawPlaceholder(GuiGraphics g, String name, int x, int y) {
        StagePaletteSyncPacket.Entry entry = stage.palette().entry(name);
        ItemStack stack = placeholderStack(name);
        String resolved = entry == null ? null : entry.blockId();
        StagePlaceholderItemRenderer.withResolved(resolved, () -> g.renderItem(stack, x, y));
        if (entry != null && entry.overridden()) {
            g.fill(x + 13, y + 13, x + 16, y + 16, OVERRIDE_DOT);
        }
    }

    private static ItemStack placeholderStack(String name) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(games.brennan.dungeontrain.DungeonTrain.MOD_ID, name);
        return BuiltInRegistries.BLOCK.containsKey(id) ? new ItemStack(BuiltInRegistries.BLOCK.get(id))
            : MenuBlockIcons.iconStackFor(id.toString());
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
        int firstBlock = pages.firstRow(page) * blockCols;
        int endBlock = Math.min(blocks.size(), pages.endRow(page) * blockCols);
        for (int idx = firstBlock; idx < endBlock; idx++) {
            int slot = idx - firstBlock;
            int x = r.x() + (slot % blockCols) * CELL;
            int y = r.y() + (slot / blockCols) * CELL;
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

    Hit hitTest(double mx, double my) {
        if (layout == null || stage == null) return Hit.NONE;
        if (pages.hasPager()) {
            switch (EditorPager.hit(pagerRect(), page, pages.pageCount(), mx, my)) {
                case PREV -> { return new Hit(HitKind.PAGE_PREV, -1); }
                case NEXT -> { return new Hit(HitKind.PAGE_NEXT, -1); }
                case NONE -> { }
            }
        }
        if (pages.kindOf(page) == SectionKind.OVERVIEW) {
            InventoryEditorLayout.Rect ir = layout.icons();
            if (my >= ir.y() && my < ir.y() + iconCell) {
                for (int i = 0; i < iconX.length; i++) {
                    if (mx >= iconX[i] && mx < iconX[i] + iconCell) return new Hit(HitKind.ICON, i);
                }
            }
            if (layout.preview().contains(mx, my)) return new Hit(HitKind.PREVIEW, 0);
            int cell = TemplateDataSheet.hit(sheetCells, mx, my);
            return cell >= 0 ? new Hit(HitKind.SHEET, cell) : Hit.NONE;
        }
        InventoryEditorLayout.Rect r = gridRect();
        if (!r.contains(mx, my)) return Hit.NONE;
        switch (pages.kindOf(page)) {
            case PALETTE, STONE -> {
                List<EditorStagePalettePage.Row> rows = paletteRowsShown();
                int idx = pages.firstRow(page) + (int) ((my - r.y()) / CELL);
                if (idx >= pages.endRow(page)) return Hit.NONE;
                EditorStagePalettePage.Row row = rows.get(idx);
                if (row.kind() == EditorStagePalettePage.Kind.FAMILY) return new Hit(HitKind.FAMILY, idx);
                if (row.isHeading()) return Hit.NONE;
                int c = (int) ((mx - cellsLeft(r, row)) / CELL);
                return mx >= cellsLeft(r, row) && c < row.names().size() ? new Hit(HitKind.CELL, idx, c) : Hit.NONE;
            }
            case BLOCKS -> {
                int col = (int) ((mx - r.x()) / CELL);
                int row = (int) ((my - r.y()) / CELL);
                if (col >= blockCols) return Hit.NONE;
                int idx = (pages.firstRow(page) + row) * blockCols + col;
                return row < pages.endRow(page) - pages.firstRow(page) && idx < stage.blocks().size()
                    ? new Hit(HitKind.BLOCK, idx) : Hit.NONE;
            }
            case TEMPLATES -> {
                int idx = pages.firstRow(page) + (int) ((my - r.y()) / ROW_H);
                return idx < pages.endRow(page) ? new Hit(HitKind.ROW, idx) : Hit.NONE;
            }
            default -> { return Hit.NONE; }
        }
    }

    /** Wheel or arrow: one page per notch, held within the pages that exist. */
    boolean scrollBy(int dir) {
        int next = pages.clamp(page + dir);
        if (next == page) return false;
        page = next;
        return true;
    }

    /**
     * An icon's label (and why it is off, or what it acts on); a sheet cell's hint; a placeholder
     * cell as {@code name → block · override}; a family heading's gesture; or the block under the
     * pointer as {@code name · ×count}.
     */
    List<String> tooltipAt(Hit hit) {
        if (stage == null) return List.of();
        switch (hit.kind()) {
            case ICON -> {
                if (hit.index() < 0 || hit.index() >= icons.size()) return List.of();
                EditorScreenActions.Icon icon = icons.get(hit.index());
                String label = EditorScreenLang.text(icon.labelKey());
                if (!icon.enabled()) {
                    return icon.disabledKey() == null ? List.of(label) : List.of(label, EditorScreenLang.text(icon.disabledKey()));
                }
                return icon.detail() == null ? List.of(label) : List.of(label, icon.detail());
            }
            case SHEET -> {
                TemplateDataSheet.Placed placed = sheetCell(hit.index());
                return placed == null || placed.cell().tooltip() == null ? List.of()
                    : List.of(placed.cell().tooltip().split("\n"));
            }
            case CELL -> {
                String name = cellName(hit);
                StagePaletteSyncPacket.Entry entry = name == null ? null : stage.palette().entry(name);
                if (entry == null) return List.of();
                String target = MenuBlockIcons.iconStackFor(entry.blockId()).getHoverName().getString();
                return List.of(name + " → " + target,
                    EditorScreenLang.text(entry.overridden() ? EditorScreenLang.STAGES_PALETTE_OVERRIDE
                        : EditorScreenLang.STAGES_PALETTE_DERIVED),
                    EditorScreenLang.text(EditorScreenLang.STAGES_PALETTE_CELL_TIP));
            }
            case FAMILY -> {
                return List.of(EditorScreenLang.text(EditorScreenLang.STAGES_PALETTE_FAMILY_TIP));
            }
            case BLOCK -> {
                if (hit.index() < 0 || hit.index() >= stage.blocks().size()) return List.of();
                StageBlocksSyncPacket.BlockCount b = stage.blocks().get(hit.index());
                String name = MenuBlockIcons.iconStackFor(b.blockId()).getHoverName().getString();
                return List.of(name, EditorScreenLang.text(EditorScreenLang.STAGES_BLOCK_TIP, b.count()));
            }
            default -> { return List.of(); }
        }
    }
}
