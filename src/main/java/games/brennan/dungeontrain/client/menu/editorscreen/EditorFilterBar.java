package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.client.builder.BuilderProfileFilters;
import games.brennan.dungeontrain.client.menu.EditorMenuScreen;
import games.brennan.dungeontrain.editor.PlotCategory;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * The rows above the grid, shared by the Templates and Layout tabs: the search box's row, and —
 * when expanded — the category strip and the type strip under it.
 *
 * <p>Collapsed, the one row reads {@code [search][▸ Filters][the active filters…]}: each active
 * chip is the filter it names and clicking it undoes that filter. Expanded, the row carries the
 * full chip set and the two strips come down under it. Both tabs read the same filter state
 * ({@link EditorScreenState}), so narrowing on one narrows the other.</p>
 *
 * <p>Hit-testing reads back the geometry of the last frame, so what was drawn is what a click
 * lands on. The search box itself belongs to the screen; this only says where it goes.</p>
 */
final class EditorFilterBar {

    static final int CHIP_PAD = 4;
    static final int CHIP_GAP = 2;
    /** The magnifier that labels the filter box, and the breathing room either side of it. */
    static final int SEARCH_ICON = 8;
    static final int SEARCH_GAP = 2;
    static final int CELL_ON = 0x8040AA40;
    static final int CELL_IDLE = 0x30FFFFFF;
    static final int CELL_HOVER = 0xB0FFCC33;
    static final String OPEN = "▾";
    static final String FOLDED = "▸";

    /** What a click landed on. */
    enum HitKind { NONE, TOGGLE, CHIP, ACTIVE, CATEGORY, STRIP, LOAD_ALL }

    record Hit(HitKind kind, int index) {
        static final Hit NONE = new Hit(HitKind.NONE, -1);
    }

    /** One chip of the expanded row: the two provenance toggles, creator mode's two, and the search. */
    private enum Kind { MINE, BUILTIN, IMPORTED, PLAYER, STATUS, STARRED }

    private record Chip(Kind kind, String label, boolean on, int x, int w) {}

    /** What undoing an active chip means. */
    enum ActiveKind { CATEGORY, TYPE, MINE, BUILTIN, IMPORTED, STATUS, STARRED, PLAYER }

    /** One filter in force, as the collapsed row shows it. */
    record ActiveChip(ActiveKind kind, String label) {}

    private record Placed(ActiveChip chip, int x, int w) {}

    private record CategoryCell(EditorCategoryFilter filter, int x, int w) {}

    private record StripCell(EditorRosterIndex.TypeStrip strip, int x, int w) {}

    /** Equal cells across a row, the last one taking the remainder; {@code reserve} stays free at the right. */
    private record Cells(int x, int w) {
        static List<Cells> across(InventoryEditorLayout.Rect row, int count, int reserve) {
            List<Cells> out = new ArrayList<>(count);
            if (count == 0) return out;
            int cellW = Math.max(1, (row.w() - reserve) / count);
            for (int i = 0; i < count; i++) {
                int x = row.x() + i * cellW;
                int w = i == count - 1 ? row.right() - reserve - x : cellW - 1;
                out.add(new Cells(x, w));
            }
            return out;
        }
    }

    /**
     * The review states the status chip cycles, in funnel order: everything → never asked → waiting →
     * decided. The same order and the same words My Builds' own chip uses.
     */
    private static final List<String[]> STATUS_OPTIONS = List.of(
        new String[] {BuilderProfileFilters.ALL, "gui.dungeontrain.builder.profile.status.all"},
        new String[] {BuilderReviewState.NONE, "gui.dungeontrain.builder.profile.status.none"},
        new String[] {BuilderReviewState.SUBMITTED, "gui.dungeontrain.builder.profile.status.submitted"},
        new String[] {BuilderReviewState.ACCEPTED, "gui.dungeontrain.builder.profile.status.accepted"},
        new String[] {BuilderReviewState.DECLINED, "gui.dungeontrain.builder.profile.status.declined"});

    private InventoryEditorLayout.Rect filterRect;
    private InventoryEditorLayout.Rect categoryRect;
    private InventoryEditorLayout.Rect stripRect;
    private InventoryEditorLayout.Rect toggleRect;
    private InventoryEditorLayout.Rect loadAllRect;
    private int boxX;
    private int boxW;
    private boolean expanded;
    private boolean creatorMode;
    private List<Chip> chips = List.of();
    private List<Placed> active = List.of();
    private List<CategoryCell> categoryCells = List.of();
    private List<StripCell> stripCells = List.of();
    private Hit hovered = Hit.NONE;

    Hit hovered() { return hovered; }
    int boxX() { return boxX; }
    int boxW() { return boxW; }
    boolean isPlayerChip(int i) { return i >= 0 && i < chips.size() && chips.get(i).kind() == Kind.PLAYER; }
    boolean isPlayerActive(int i) { return i >= 0 && i < active.size() && active.get(i).chip().kind() == ActiveKind.PLAYER; }
    EditorCategoryFilter categoryAt(int i) { return i >= 0 && i < categoryCells.size() ? categoryCells.get(i).filter() : null; }
    EditorRosterIndex.TypeStrip stripAt(int i) { return i >= 0 && i < stripCells.size() ? stripCells.get(i).strip() : null; }

    // ------------------------------------------------------------------
    // State helpers
    // ------------------------------------------------------------------

    /**
     * Whether the imported chip is offered: a dev-mode affordance, since telling an installed
     * package's rooms from your own belongs to the same job as writing templates back to source.
     */
    static boolean showImportedChip() {
        return EditorStatusHudOverlay.isDevModeOn();
    }

    /** Whether the creator search is offered: gated like the DevMode row, it is for reviewers. */
    static boolean showCreatorChip() {
        return EditorMenuScreen.shouldShowDevModeToggle(VersionInfo.BRANCH);
    }

    /** The state after this one, wrapping round — an unknown one starts the cycle over at All. */
    static String nextStatus(String current) {
        for (int i = 0; i < STATUS_OPTIONS.size(); i++) {
            if (STATUS_OPTIONS.get(i)[0].equals(current)) {
                return STATUS_OPTIONS.get((i + 1) % STATUS_OPTIONS.size())[0];
            }
        }
        return BuilderProfileFilters.ALL;
    }

    /** What the status chip says for a review state. */
    static String statusLabel(String review) {
        for (String[] option : STATUS_OPTIONS) {
            if (option[0].equals(review)) return EditorScreenLang.text(option[1]);
        }
        return EditorScreenLang.text(STATUS_OPTIONS.get(0)[1]);
    }

    /** The chip's label: whose builds are loaded, or the invitation to go and find someone. */
    static String creatorLabel() {
        if (EditorCreatorBuilds.pooled()) return EditorScreenLang.text(EditorScreenLang.CREATOR_POOL);
        String viewed = EditorCreatorBuilds.viewedName();
        return viewed == null || viewed.isEmpty()
            ? EditorScreenLang.text(EditorScreenLang.FILTER_FIND_CREATOR)
            : EditorScreenLang.text(EditorScreenLang.FILTER_CREATOR, viewed);
    }

    /**
     * The filters in force, in row order — what the collapsed row shows.
     *
     * <p>A category other than All, a chosen type strip, each provenance chip that is on; in creator
     * mode the review state when it is not All and Starred when on, then whose builds are loaded.
     * Pure, so the row's contents are pinned by a test without a font.</p>
     */
    static List<ActiveChip> activeChips(EditorCategoryFilter category, String typeName,
                                        EditorRosterIndex.Filters filters, boolean creatorMode,
                                        String review, boolean starred, boolean showImported,
                                        boolean showCreator, String creatorLabel) {
        List<ActiveChip> out = new ArrayList<>();
        if (category != null && category != EditorCategoryFilter.ALL) {
            out.add(new ActiveChip(ActiveKind.CATEGORY, EditorScreenLang.text(category.langKey())));
        }
        if (typeName != null && !typeName.isEmpty() && category != EditorCategoryFilter.ALL) {
            out.add(new ActiveChip(ActiveKind.TYPE, typeName));
        }
        if (!creatorMode) {
            if (filters.mine()) out.add(new ActiveChip(ActiveKind.MINE, EditorScreenLang.text(EditorScreenLang.FILTER_MINE)));
            if (filters.builtin()) out.add(new ActiveChip(ActiveKind.BUILTIN, EditorScreenLang.text(EditorScreenLang.FILTER_BUILTIN)));
            if (filters.imported() && showImported) {
                out.add(new ActiveChip(ActiveKind.IMPORTED, EditorScreenLang.text(EditorScreenLang.FILTER_IMPORTED)));
            }
        } else {
            if (!BuilderProfileFilters.ALL.equals(review)) out.add(new ActiveChip(ActiveKind.STATUS, statusLabel(review)));
            if (starred) out.add(new ActiveChip(ActiveKind.STARRED, EditorScreenLang.text(EditorScreenLang.FILTER_STARRED)));
        }
        if (showCreator && creatorMode) out.add(new ActiveChip(ActiveKind.PLAYER, creatorLabel));
        return out;
    }

    private static List<ActiveChip> activeChipsNow() {
        return activeChips(EditorScreenState.category(), EditorScreenState.typeName(), EditorScreenState.filters(),
            EditorCreatorBuilds.active(), EditorScreenState.creatorReview(), EditorScreenState.creatorStarred(),
            showImportedChip(), showCreatorChip(), creatorLabel());
    }

    /** Apply the click on expanded chip {@code i} to the current filters, or return them unchanged. */
    EditorRosterIndex.Filters applyChip(int i, EditorRosterIndex.Filters current) {
        if (i < 0 || i >= chips.size()) return current;
        return switch (chips.get(i).kind()) {
            // Without the imported chip there is no way to ask for package content by name, so
            // Mine carries it — see Filters.withMineCarryingImported.
            case MINE -> showImportedChip()
                ? current.withMine(!current.mine())
                : current.withMineCarryingImported(!current.mine());
            case BUILTIN -> current.withBuiltin(!current.builtin());
            case IMPORTED -> current.withImported(!current.imported());
            case PLAYER, STATUS, STARRED -> current;
        };
    }

    /** Apply a click on one of creator mode's own chips, if that is what it was. */
    boolean applyCreatorChip(int i) {
        if (i < 0 || i >= chips.size()) return false;
        switch (chips.get(i).kind()) {
            case STATUS -> EditorScreenState.setCreatorReview(nextStatus(EditorScreenState.creatorReview()));
            case STARRED -> EditorScreenState.setCreatorStarred(!EditorScreenState.creatorStarred());
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Undo active chip {@code i}. The creator chip is not a filter to undo; see {@link #isPlayerActive}. */
    boolean applyActive(int i) {
        if (i < 0 || i >= active.size()) return false;
        EditorRosterIndex.Filters f = EditorScreenState.filters();
        switch (active.get(i).chip().kind()) {
            case CATEGORY -> EditorScreenState.setCategory(EditorCategoryFilter.ALL);
            case TYPE -> EditorScreenState.setTypeName("");
            case MINE -> EditorScreenState.setFilters(showImportedChip() ? f.withMine(false) : f.withMineCarryingImported(false));
            case BUILTIN -> EditorScreenState.setFilters(f.withBuiltin(false));
            case IMPORTED -> EditorScreenState.setFilters(f.withImported(false));
            case STATUS -> EditorScreenState.setCreatorReview(BuilderProfileFilters.ALL);
            case STARRED -> EditorScreenState.setCreatorStarred(false);
            case PLAYER -> {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private static int chipW(Font font, String label) {
        return font.width(label) + CHIP_PAD * 2;
    }

    private String toggleLabel() {
        return (expanded ? OPEN : FOLDED) + " " + EditorScreenLang.text(EditorScreenLang.FILTERS);
    }

    /** The expanded row's chip labels, in order — what the box has to leave room for. */
    private static List<String> chipLabels(boolean creatorMode) {
        List<String> out = new ArrayList<>(4);
        // A builder's uploads carry no provenance to narrow, so in their place come the two that
        // DO say something about a relay build.
        if (!creatorMode) {
            out.add(EditorScreenLang.text(EditorScreenLang.FILTER_MINE));
            out.add(EditorScreenLang.text(EditorScreenLang.FILTER_BUILTIN));
            if (showImportedChip()) out.add(EditorScreenLang.text(EditorScreenLang.FILTER_IMPORTED));
        } else {
            out.add(statusLabel(EditorScreenState.creatorReview()));
            out.add(EditorScreenLang.text(EditorScreenLang.FILTER_STARRED));
        }
        if (showCreatorChip()) out.add(creatorLabel());
        return out;
    }

    /**
     * Lay the bar out for this frame. {@code showLoadAll} is the Templates tab's: the button stamps
     * plots, which the spawn table has no business doing.
     */
    void layout(InventoryEditorLayout layout, Font font, EditorRosterIndex index, boolean showLoadAll) {
        filterRect = layout.filter();
        categoryRect = layout.categoryStrip();
        stripRect = layout.typeStrip();
        expanded = EditorScreenState.filtersExpanded();
        creatorMode = EditorCreatorBuilds.active();

        // The row: search box, toggle, then the chips — the box takes whatever the rest leaves.
        List<String> labels = new ArrayList<>();
        List<ActiveChip> activeNow = expanded ? List.of() : activeChipsNow();
        if (expanded) labels.addAll(chipLabels(creatorMode));
        else for (ActiveChip a : activeNow) labels.add(a.label());
        int trailing = chipW(font, toggleLabel()) + CHIP_GAP;
        for (String label : labels) trailing += chipW(font, label) + CHIP_GAP;
        boxX = filterRect.x() + SEARCH_GAP + SEARCH_ICON + SEARCH_GAP;
        boxW = Math.max(24, filterRect.right() - trailing - CHIP_GAP - boxX);

        int cx = boxX + boxW + CHIP_GAP;
        int toggleW = chipW(font, toggleLabel());
        toggleRect = new InventoryEditorLayout.Rect(cx, filterRect.y(), toggleW, filterRect.h());
        cx += toggleW + CHIP_GAP;

        List<Chip> c = new ArrayList<>();
        List<Placed> a = new ArrayList<>();
        if (expanded) {
            EditorRosterIndex.Filters filters = EditorScreenState.filters();
            if (!creatorMode) {
                cx = addChip(c, Kind.MINE, EditorScreenLang.text(EditorScreenLang.FILTER_MINE), filters.mine(), cx, font);
                cx = addChip(c, Kind.BUILTIN, EditorScreenLang.text(EditorScreenLang.FILTER_BUILTIN), filters.builtin(), cx, font);
                if (showImportedChip()) {
                    cx = addChip(c, Kind.IMPORTED, EditorScreenLang.text(EditorScreenLang.FILTER_IMPORTED), filters.imported(), cx, font);
                }
            } else {
                cx = addChip(c, Kind.STATUS, statusLabel(EditorScreenState.creatorReview()),
                    !BuilderProfileFilters.ALL.equals(EditorScreenState.creatorReview()), cx, font);
                cx = addChip(c, Kind.STARRED, EditorScreenLang.text(EditorScreenLang.FILTER_STARRED),
                    EditorScreenState.creatorStarred(), cx, font);
            }
            if (showCreatorChip()) addChip(c, Kind.PLAYER, creatorLabel(), creatorMode, cx, font);
        } else {
            for (ActiveChip chip : activeNow) {
                int w = chipW(font, chip.label());
                a.add(new Placed(chip, cx, w));
                cx += w + CHIP_GAP;
            }
        }
        chips = c;
        active = a;

        // The two strips, only when expanded. The type strip is empty under All and for a
        // builder's uploads — fifteen strips would not fit the row, and the point of All is the
        // roster without one.
        List<CategoryCell> cat = new ArrayList<>();
        List<StripCell> sc = new ArrayList<>();
        loadAllRect = null;
        if (expanded) {
            EditorCategoryFilter[] filters_ = EditorCategoryFilter.values();
            List<Cells> cc = Cells.across(categoryRect, filters_.length, 0);
            for (int i = 0; i < filters_.length; i++) {
                cat.add(new CategoryCell(filters_[i], cc.get(i).x(), cc.get(i).w()));
            }
            PlotCategory page = EditorScreenState.category().category();
            boolean loadAll = showLoadAll && !creatorMode && page != null;
            int reserve = 0;
            if (loadAll) {
                int w = chipW(font, EditorScreenLang.text(EditorScreenLang.LOAD_ALL));
                loadAllRect = new InventoryEditorLayout.Rect(stripRect.right() - w, stripRect.y(), w, stripRect.h());
                reserve = w + CHIP_GAP;
            }
            List<EditorRosterIndex.TypeStrip> strips = page == null || creatorMode ? List.of() : index.typeStrips(page);
            List<Cells> cells = Cells.across(stripRect, strips.size(), reserve);
            for (int i = 0; i < strips.size(); i++) {
                sc.add(new StripCell(strips.get(i), cells.get(i).x(), cells.get(i).w()));
            }
        }
        categoryCells = cat;
        stripCells = sc;
    }

    private static int addChip(List<Chip> out, Kind kind, String label, boolean on, int x, Font font) {
        int w = chipW(font, label);
        out.add(new Chip(kind, label, on, x, w));
        return x + w + CHIP_GAP;
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        hovered = hitTest(mouseX, mouseY);
        // The magnifier in front of the filter box — the box itself is drawn by the screen, which
        // owns the widget and scissors it so its text cannot spill across the chips.
        g.blitSprite(EditorIcons.SEARCH, filterRect.x() + SEARCH_GAP,
            filterRect.y() + (filterRect.h() - SEARCH_ICON) / 2, SEARCH_ICON, SEARCH_ICON);

        // Clipped to the row: a long list of chips stops at the panel's edge rather than running on.
        g.enableScissor(filterRect.x(), filterRect.y(), filterRect.right(), filterRect.bottom());
        cell(g, font, toggleRect.x(), toggleRect.w(), filterRect, toggleLabel(), expanded,
            hovered.kind() == HitKind.TOGGLE, false);
        for (int i = 0; i < chips.size(); i++) {
            Chip chip = chips.get(i);
            cell(g, font, chip.x(), chip.w(), filterRect, chip.label(), chip.on(),
                hovered.kind() == HitKind.CHIP && hovered.index() == i, false);
        }
        for (int i = 0; i < active.size(); i++) {
            Placed p = active.get(i);
            cell(g, font, p.x(), p.w(), filterRect, p.chip().label(), true,
                hovered.kind() == HitKind.ACTIVE && hovered.index() == i, false);
        }
        g.disableScissor();
        if (!expanded) return;

        // Category strip. The cell of the plot the author stands in wears the green mark the tab
        // used to, so the category is readable from any other cell.
        EditorCategoryFilter activeCell = EditorScreenState.category();
        VariantKey standing = EditorScreenState.standingIn();
        EditorCategoryFilter hereCell = standing == null ? null : EditorCategoryFilter.forCategory(standing.category());
        for (int i = 0; i < categoryCells.size(); i++) {
            CategoryCell c = categoryCells.get(i);
            boolean hov = hovered.kind() == HitKind.CATEGORY && hovered.index() == i;
            cell(g, font, c.x(), c.w(), categoryRect, EditorScreenLang.text(c.filter().langKey()),
                c.filter() == activeCell, hov, true);
            if (c.filter() == hereCell) {
                int ty = categoryRect.y() + (categoryRect.h() - font.lineHeight) / 2 + 1;
                g.fill(c.x() + c.w() - 6, ty + 1, c.x() + c.w() - 3, ty + 4, TemplateTilePainter.HERE);
            }
        }

        if (loadAllRect != null) {
            boolean hov = hovered.kind() == HitKind.LOAD_ALL;
            g.fill(loadAllRect.x(), loadAllRect.y(), loadAllRect.right(), loadAllRect.bottom(), hov ? CELL_HOVER : CELL_ON);
            g.drawString(font, EditorScreenLang.text(EditorScreenLang.LOAD_ALL), loadAllRect.x() + CHIP_PAD,
                stripRect.y() + (stripRect.h() - font.lineHeight) / 2 + 1, hov ? 0xFF000000 : 0xFFFFFFFF, false);
        }
        String activeType = EditorScreenState.effectiveTypeName(EditorRosterClient.index());
        for (int i = 0; i < stripCells.size(); i++) {
            StripCell c = stripCells.get(i);
            boolean hov = hovered.kind() == HitKind.STRIP && hovered.index() == i;
            cell(g, font, c.x(), c.w(), stripRect, c.strip().typeName() + " " + c.strip().count(),
                c.strip().typeName().equals(activeType), hov, true);
        }
    }

    /** One cell of a row: tinted by state, its label centred (strips) or padded (chips), clipped. */
    private static void cell(GuiGraphics g, Font font, int x, int w, InventoryEditorLayout.Rect row,
                             String label, boolean on, boolean hov, boolean centred) {
        g.fill(x, row.y(), x + w, row.bottom(), hov ? CELL_HOVER : (on ? CELL_ON : CELL_IDLE));
        String text = font.plainSubstrByWidth(label, w - 4);
        int tx = centred ? x + (w - font.width(text)) / 2 : x + CHIP_PAD;
        g.drawString(font, text, tx, row.y() + (row.h() - font.lineHeight) / 2 + 1,
            hov ? 0xFF000000 : 0xFFFFFFFF, false);
    }

    // ------------------------------------------------------------------
    // Hit-testing
    // ------------------------------------------------------------------

    Hit hitTest(double mx, double my) {
        if (filterRect == null) return Hit.NONE;
        if (filterRect.contains(mx, my)) {
            if (toggleRect.contains(mx, my)) return new Hit(HitKind.TOGGLE, 0);
            for (int i = 0; i < chips.size(); i++) {
                Chip c = chips.get(i);
                if (mx >= c.x() && mx < c.x() + c.w()) return new Hit(HitKind.CHIP, i);
            }
            for (int i = 0; i < active.size(); i++) {
                Placed p = active.get(i);
                if (mx >= p.x() && mx < p.x() + p.w()) return new Hit(HitKind.ACTIVE, i);
            }
            return Hit.NONE;
        }
        if (!expanded) return Hit.NONE;
        if (categoryRect.contains(mx, my)) {
            for (int i = 0; i < categoryCells.size(); i++) {
                CategoryCell c = categoryCells.get(i);
                if (mx >= c.x() && mx < c.x() + c.w()) return new Hit(HitKind.CATEGORY, i);
            }
            return Hit.NONE;
        }
        if (loadAllRect != null && loadAllRect.contains(mx, my)) return new Hit(HitKind.LOAD_ALL, 0);
        if (stripRect.contains(mx, my)) {
            for (int i = 0; i < stripCells.size(); i++) {
                StripCell c = stripCells.get(i);
                if (mx >= c.x() && mx < c.x() + c.w()) return new Hit(HitKind.STRIP, i);
            }
        }
        return Hit.NONE;
    }

    String tooltipAt(Hit hit) {
        return switch (hit.kind()) {
            case LOAD_ALL -> EditorScreenLang.text(EditorScreenLang.LOAD_ALL_TIP);
            case TOGGLE -> EditorScreenLang.text(expanded ? EditorScreenLang.FILTERS_HIDE : EditorScreenLang.FILTERS_SHOW);
            case ACTIVE -> hit.index() >= 0 && hit.index() < active.size()
                && active.get(hit.index()).chip().kind() != ActiveKind.PLAYER
                ? EditorScreenLang.text(EditorScreenLang.FILTERS_CLEAR_ONE) : null;
            default -> null;
        };
    }
}
