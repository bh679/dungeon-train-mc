package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.builder.BuilderProfileState;
import games.brennan.dungeontrain.client.builder.BuilderTileSpin;
import games.brennan.dungeontrain.client.builder.RelayBuildPreviews;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.client.menu.EditorMenuScreen;
import games.brennan.dungeontrain.client.menu.EditorSaveStatus;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * The left pane: filter chips, the type strip, the tile grid, and the sub-variant grid that
 * opens under a selected group.
 *
 * <p>Everything scrolls together inside the grid rect. Hit-testing reads back the geometry of
 * the last frame, so what was drawn is what a click lands on.</p>
 */
public final class EditorBrowserPane {

    static final int TILE_GAP = 3;
    static final int SUB_HEADER_H = 12;
    static final int SUB_GAP = 6;
    static final int CHIP_PAD = 4;
    static final int CHIP_GAP = 2;
    /** The magnifier that labels the filter box, and the breathing room either side of it. */
    static final int SEARCH_ICON = 8;
    static final int SEARCH_GAP = 2;
    static final int CELL_ON = 0x8040AA40;
    static final int CELL_IDLE = 0x30FFFFFF;
    static final int CELL_HOVER = 0xB0FFCC33;
    static final int SUB_HEADER_BG = 0xD0000000;

    /** What a click landed on. */
    public enum HitKind { NONE, CHIP, STRIP, TILE, NEW, SUB_TILE, NEW_SUB, CREATOR_TILE }

    public record Hit(HitKind kind, int index) {
        public static final Hit NONE = new Hit(HitKind.NONE, -1);
    }

    /**
     * One chip of the filter row.
     *
     * <p>{@code kind} says what a click does: the two provenance chips toggle, and the creator chip
     * — a developer's tool, absent on release builds — opens a search for a player whose uploaded
     * builds you want to look through.</p>
     */
    private record Chip(Kind kind, String label, boolean on, int x, int w) {}

    /** {@code PLAYER} is not a filter: it opens the creator search. See {@link #isPlayerChip}. */
    private enum Kind { MINE, BUILTIN, PLAYER }

    /** One cell of the type strip. */
    private record StripCell(EditorRosterIndex.TypeStrip strip, int x, int w) {}

    private final BuilderTileSpin spin = new BuilderTileSpin();

    private int scroll;
    private List<Chip> chips = List.of();
    private List<StripCell> stripCells = List.of();
    private List<EditorRosterIndex.Tile> tiles = List.of();
    private List<EditorRosterIndex.Tile> subTiles = List.of();
    /** The loaded builder's uploads, drawn in the main grid in place of the roster's tiles. */
    private List<BuilderProfilePacket.Entry> creatorTiles = List.of();
    private boolean creatorMode;
    private EditorRosterIndex.Tile subParent;
    private TemplateTileGridLayout mainGrid;
    private TemplateTileGridLayout subGrid;
    private InventoryEditorLayout.Rect gridRect;
    private InventoryEditorLayout.Rect filterRect;
    private InventoryEditorLayout.Rect stripRect;
    private int contentHeight;
    private Hit hovered = Hit.NONE;

    public List<EditorRosterIndex.Tile> tiles() { return tiles; }
    public List<BuilderProfilePacket.Entry> creatorTiles() { return creatorTiles; }
    public List<EditorRosterIndex.Tile> subTiles() { return subTiles; }
    public EditorRosterIndex.Tile subParent() { return subParent; }
    public EditorRosterIndex.TypeStrip stripAt(int i) { return i >= 0 && i < stripCells.size() ? stripCells.get(i).strip() : null; }
    /** Apply the click on chip {@code i} to the current filters, or return them unchanged. */
    public EditorRosterIndex.Filters applyChip(int i, EditorRosterIndex.Filters current) {
        if (i < 0 || i >= chips.size()) return current;
        return switch (chips.get(i).kind()) {
            case MINE -> current.withMine(!current.mine());
            case BUILTIN -> current.withBuiltin(!current.builtin());
            // Not a filter — the screen opens the search for it.
            case PLAYER -> current;
        };
    }

    /** True when chip {@code i} is the creator search rather than a filter toggle. */
    public boolean isPlayerChip(int i) {
        return i >= 0 && i < chips.size() && chips.get(i).kind() == Kind.PLAYER;
    }

    /**
     * Whether the creator search is offered at all.
     *
     * <p>Gated exactly like the DevMode row: it is for whoever is reviewing what players have
     * uploaded, and a release build has nothing to review.</p>
     */
    static boolean showCreatorChip() {
        return EditorMenuScreen.shouldShowDevModeToggle(VersionInfo.BRANCH);
    }

    /** The chip's label: whose builds are loaded, or the invitation to go and find someone. */
    static String creatorLabel() {
        String viewed = EditorCreatorBuilds.viewedName();
        return viewed == null || viewed.isEmpty()
            ? EditorScreenLang.text(EditorScreenLang.FILTER_FIND_CREATOR)
            : EditorScreenLang.text(EditorScreenLang.FILTER_CREATOR, viewed);
    }

    private static Chip chip(Kind kind, String label, boolean on, int x, Font font) {
        return new Chip(kind, label, on, x, font.width(label) + CHIP_PAD * 2);
    }

    private static Chip last(List<Chip> chips) {
        return chips.get(chips.size() - 1);
    }
    public Hit hovered() { return hovered; }

    /** Where the filter text box starts — after the search icon that labels it. */
    public int filterBoxX(InventoryEditorLayout.Rect filter) {
        return filter.x() + SEARCH_GAP + SEARCH_ICON + SEARCH_GAP;
    }

    /**
     * How wide the filter text box may be: whatever the chips leave.
     *
     * <p>It can end up narrow, which is why the screen draws the box inside a scissor and drops
     * the hint when it no longer fits — the text has to disappear behind the chips rather than
     * run across them.</p>
     */
    public int filterBoxWidth(InventoryEditorLayout.Rect filter, Font font) {
        int chipsW = 0;
        for (String label : chipLabels()) {
            chipsW += font.width(label) + CHIP_PAD * 2 + CHIP_GAP;
        }
        return Math.max(24, filter.right() - chipsW - CHIP_GAP - filterBoxX(filter));
    }

    /** The chips' labels, in row order — what the box has to leave room for. */
    private static List<String> chipLabels() {
        List<String> out = new ArrayList<>(3);
        // A builder's uploads carry no provenance to narrow: the two roster chips would sit there
        // saying nothing about what is on screen, so only the one naming whose builds these are is
        // offered — and it is the way back out.
        if (!EditorCreatorBuilds.active()) {
            out.add(EditorScreenLang.text(EditorScreenLang.FILTER_MINE));
            out.add(EditorScreenLang.text(EditorScreenLang.FILTER_BUILTIN));
        }
        if (showCreatorChip()) out.add(creatorLabel());
        return out;
    }

    /** Lay the pane out for this frame from the roster and the remembered state. */
    public void layout(InventoryEditorLayout layout, Font font, EditorRosterIndex index) {
        filterRect = layout.filter();
        stripRect = layout.typeStrip();
        gridRect = layout.grid();

        // Chips, right-aligned in the filter row after the text box.
        EditorRosterIndex.Filters filters = EditorScreenState.filters();
        creatorMode = EditorCreatorBuilds.active();
        List<Chip> c = new ArrayList<>();
        int cx = filterBoxX(filterRect) + filterBoxWidth(filterRect, font) + CHIP_GAP;
        if (!creatorMode) {
            c.add(chip(Kind.MINE, EditorScreenLang.text(EditorScreenLang.FILTER_MINE),
                filters.mine(), cx, font));
            cx = last(c).x() + last(c).w() + CHIP_GAP;
            c.add(chip(Kind.BUILTIN, EditorScreenLang.text(EditorScreenLang.FILTER_BUILTIN),
                filters.builtin(), cx, font));
            cx = last(c).x() + last(c).w() + CHIP_GAP;
        }
        if (showCreatorChip()) {
            c.add(chip(Kind.PLAYER, creatorLabel(), creatorMode, cx, font));
        }
        chips = c;

        // Type strip: equal cells across the row. The All page has none — fifteen strips would not
        // fit the row, and its whole point is the roster without one.
        PlotCategory page = EditorScreenState.page().category();
        List<EditorRosterIndex.TypeStrip> strips = page == null || creatorMode
            ? List.of() : index.typeStrips(page);
        List<StripCell> sc = new ArrayList<>();
        if (!strips.isEmpty()) {
            int cellW = stripRect.w() / strips.size();
            for (int i = 0; i < strips.size(); i++) {
                int x = stripRect.x() + i * cellW;
                int w = i == strips.size() - 1 ? stripRect.right() - x : cellW - 1;
                sc.add(new StripCell(strips.get(i), x, w));
            }
        }
        stripCells = sc;

        // Somebody else's uploads take the main grid whole: they are not roster tiles, have no
        // type strip to sit under and no sub-variants to open, so every other list goes empty for
        // the duration rather than being drawn against a roster this grid is not showing.
        creatorTiles = creatorMode
            ? EditorCreatorBuilds.forPage(EditorScreenState.page(), EditorScreenState.text())
            : List.of();

        // Tiles of the active strip, filtered.
        String typeName = EditorScreenState.effectiveTypeName(index);
        List<EditorRosterIndex.Tile> all = creatorMode ? List.of() : (page == null
            ? (EditorScreenState.page() == EditorScreenPage.ALL ? index.allTiles() : List.of())
            : index.tiles(page, typeName));
        tiles = EditorRosterIndex.standingFirst(
            EditorRosterIndex.filter(all, EditorScreenState.filters(), EditorScreenState.text()),
            EditorScreenState.standingIn());

        // The sub-variant grid, when the selection is a group or a member of one.
        subParent = null;
        subTiles = List.of();
        VariantKey sel = creatorMode ? null : EditorScreenState.selection();
        if (sel != null) {
            EditorRosterIndex.Tile selTile = index.find(sel);
            EditorRosterIndex.Tile parent = selTile == null ? null
                : (selTile.key().isSubVariant() ? index.parentOf(selTile.key()) : (selTile.isGroup() ? selTile : null));
            if (parent != null && page != null && index.groupOf(parent.key()) != null
                && index.groupOf(parent.key()).typeName().equals(typeName)) {
                subParent = parent;
                subTiles = EditorRosterIndex.standingFirst(
                    EditorRosterIndex.subVariants(parent, EditorScreenState.filters(), EditorScreenState.text()),
                    EditorScreenState.standingIn());
            }
        }

        int tile = layout.tile();
        mainGrid = TemplateTileGridLayout.of(gridRect.x(), gridRect.y(), gridRect.w(), gridRect.h(), tile, TILE_GAP);
        // No "+" cell in creator mode: there is nothing here this editor can add to.
        int mainCount = creatorMode ? creatorTiles.size() : tiles.size() + 1;
        int subTop = gridRect.y() + mainGrid.contentHeight(mainCount) + SUB_GAP + SUB_HEADER_H + 2;
        subGrid = TemplateTileGridLayout.of(gridRect.x(), subTop, gridRect.w(), gridRect.h(), tile, TILE_GAP);
        contentHeight = subParent == null
            ? mainGrid.contentHeight(mainCount)
            : (subTop - gridRect.y()) + subGrid.contentHeight(subTiles.size() + 2);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - gridRect.h())));
    }

    public void resetScroll() {
        scroll = 0;
    }

    public boolean scrollBy(int rows) {
        int max = Math.max(0, contentHeight - (gridRect == null ? 0 : gridRect.h()));
        int next = Math.max(0, Math.min(scroll + rows * 24, max));
        boolean moved = next != scroll;
        scroll = next;
        return moved || max > 0;
    }

    public boolean overGrid(double mx, double my) {
        return gridRect != null && gridRect.contains(mx, my);
    }

    public void render(GuiGraphics g, Font font, EditorScreenTheme theme, float seconds,
                       int mouseX, int mouseY) {
        hovered = hitTest(mouseX, mouseY);

        // The magnifier in front of the filter box — the box itself is drawn by the screen, which
        // owns the widget and scissors it so its text cannot spill across the chips.
        g.blitSprite(EditorIcons.SEARCH, filterRect.x() + SEARCH_GAP,
            filterRect.y() + (filterRect.h() - SEARCH_ICON) / 2, SEARCH_ICON, SEARCH_ICON);

        // Filter chips.
        for (int i = 0; i < chips.size(); i++) {
            Chip chip = chips.get(i);
            boolean on = chip.on();
            boolean hov = hovered.kind() == HitKind.CHIP && hovered.index() == i;
            g.fill(chip.x(), filterRect.y(), chip.x() + chip.w(), filterRect.bottom(),
                hov ? CELL_HOVER : (on ? CELL_ON : CELL_IDLE));
            g.drawString(font, chip.label(), chip.x() + CHIP_PAD,
                filterRect.y() + (filterRect.h() - font.lineHeight) / 2 + 1, hov ? 0xFF000000 : 0xFFFFFFFF, false);
        }

        // Type strip.
        String active = EditorScreenState.effectiveTypeName(EditorRosterClient.index());
        for (int i = 0; i < stripCells.size(); i++) {
            StripCell cell = stripCells.get(i);
            boolean on = cell.strip().typeName().equals(active);
            boolean hov = hovered.kind() == HitKind.STRIP && hovered.index() == i;
            g.fill(cell.x(), stripRect.y(), cell.x() + cell.w(), stripRect.bottom(),
                hov ? CELL_HOVER : (on ? CELL_ON : CELL_IDLE));
            String label = cell.strip().typeName() + " " + cell.strip().count();
            label = font.plainSubstrByWidth(label, cell.w() - 4);
            g.drawString(font, label, cell.x() + (cell.w() - font.width(label)) / 2,
                stripRect.y() + (stripRect.h() - font.lineHeight) / 2 + 1, hov ? 0xFF000000 : 0xFFFFFFFF, false);
        }

        // Grids, scissored to the grid rect.
        g.enableScissor(gridRect.x(), gridRect.y(), gridRect.right(), gridRect.bottom());
        VariantKey selection = EditorScreenState.selection();
        VariantKey standing = EditorScreenState.standingIn();
        if (creatorMode) {
            drawCreatorTiles(g, font, seconds);
            g.disableScissor();
            return;
        }
        drawTiles(g, font, mainGrid, tiles, selection, standing, seconds, HitKind.TILE, HitKind.NEW, null);
        if (subParent != null) {
            int headerY = gridRect.y() + mainGrid.contentHeight(tiles.size() + 1) + SUB_GAP - scroll;
            g.fill(gridRect.x(), headerY, gridRect.right(), headerY + SUB_HEADER_H, SUB_HEADER_BG);
            String header = EditorScreenLang.text(EditorScreenLang.SUB_VARIANTS_OF, subParent.key().displayName());
            g.drawString(font, font.plainSubstrByWidth(header, gridRect.w() - 6), gridRect.x() + 3,
                headerY + (SUB_HEADER_H - font.lineHeight) / 2 + 1, MenuRowPainterColours.HEADER, false);
            drawTiles(g, font, subGrid, subTiles, selection, standing, seconds, HitKind.SUB_TILE, HitKind.NEW_SUB, subParent);
        }
        g.disableScissor();
    }

    /** The loaded builder's uploads: the same tile the roster draws, ringed by its review state. */
    private void drawCreatorTiles(GuiGraphics g, Font font, float seconds) {
        for (int i = 0; i < creatorTiles.size(); i++) {
            if (!mainGrid.isVisible(i, scroll)) continue;
            BuilderProfilePacket.Entry entry = creatorTiles.get(i);
            boolean hov = hovered.kind() == HitKind.CREATOR_TILE && hovered.index() == i;
            boolean selected = entry.relayId() == EditorCreatorBuilds.selectedId();
            TemplateArt art = EditorCreatorBuilds.artOf(entry);
            int x = mainGrid.xFor(i);
            int y = mainGrid.yFor(i, scroll);
            int size = mainGrid.tile();
            float yaw = spin.advance(art == null ? String.valueOf(entry.relayId()) : art.spinKey(), hov, seconds);
            // On screen, so worth a picture: the ask is cheap to repeat and the cache answers for
            // everything already here.
            RelayBuildPreviews.request(entry.relayId(), entry.ownerUuid(), BuilderProfileState.live());
            TemplateTilePainter.draw(g, font, art, EditorCreatorBuilds.label(entry),
                EditorPlotLabelsPacket.NO_WEIGHT, x, y, size, yaw,
                new TemplateTilePainter.Marks(selected, hov, false, false, false), entry.relayId());
            int border = BuilderReviewState.borderColourFor(entry.review());
            if (border != BuilderReviewState.BORDER_NONE) g.renderOutline(x, y, size, size, border);
        }
    }

    /** One grid: a self tile first when it is a sub-variant grid, then the tiles, then "+". */
    private void drawTiles(GuiGraphics g, Font font, TemplateTileGridLayout grid,
                           List<EditorRosterIndex.Tile> list, VariantKey selection, VariantKey standing,
                           float seconds, HitKind tileKind, HitKind newKind, EditorRosterIndex.Tile self) {
        int offset = self == null ? 0 : 1;
        int count = list.size() + offset;
        if (self != null && grid.isVisible(0, scroll)) {
            drawTile(g, font, self, true, grid.xFor(0), grid.yFor(0, scroll), grid.tile(), selection, standing, seconds,
                hovered.kind() == tileKind && hovered.index() == -1);
        }
        for (int i = 0; i < list.size(); i++) {
            int slot = i + offset;
            if (!grid.isVisible(slot, scroll)) continue;
            drawTile(g, font, list.get(i), false, grid.xFor(slot), grid.yFor(slot, scroll), grid.tile(),
                selection, standing, seconds, hovered.kind() == tileKind && hovered.index() == i);
        }
        if (grid.isVisible(count, scroll)) {
            TemplateTilePainter.drawNew(g, font, grid.xFor(count), grid.yFor(count, scroll), grid.tile(),
                hovered.kind() == newKind);
        }
    }

    private void drawTile(GuiGraphics g, Font font, EditorRosterIndex.Tile tile, boolean asSelf,
                          int x, int y, int size, VariantKey selection, VariantKey standing, float seconds,
                          boolean hov) {
        EditorTypeMenusPacket.Variant v = tile.variant();
        VariantKey key = tile.key();
        boolean selected = selection != null && selection.equals(key)
            || (asSelf && selection != null && selection.sameTemplate(key) && !selection.isSubVariant());
        boolean here = standing != null && standing.sameTemplate(key);
        PlotCategory cat = key.category();
        boolean dirty = EditorSaveStatus.isDirty(EditorStatusHudOverlay.unsavedList(), cat.id(),
            EditorSaveStatus.dirtyKey(cat, key.modelId(), key.modelName()));
        TemplateArt art = TemplateArt.of(key);
        float yaw = spin.advance(art == null ? key.toString() : art.spinKey(), hov, seconds);
        int weight = asSelf ? tile.selfWeight() : v.weight();
        String name = asSelf ? EditorScreenLang.text(EditorScreenLang.TILE_SELF, v.name()) : v.name();
        TemplateTilePainter.draw(g, font, art, name, weight, x, y, size, yaw,
            new TemplateTilePainter.Marks(selected, hov, here, dirty, !asSelf && tile.isGroup()));
    }

    /** The tile under the point, for the tooltip; null over nothing. */
    public String tooltipAt(Hit hit) {
        return switch (hit.kind()) {
            case TILE -> hit.index() >= 0 && hit.index() < tiles.size() ? tooltipFor(tiles.get(hit.index()), false) : null;
            case SUB_TILE -> hit.index() == -1 && subParent != null ? tooltipFor(subParent, true)
                : hit.index() >= 0 && hit.index() < subTiles.size() ? tooltipFor(subTiles.get(hit.index()), false) : null;
            case CREATOR_TILE -> hit.index() >= 0 && hit.index() < creatorTiles.size()
                ? tooltipFor(creatorTiles.get(hit.index())) : null;
            case NEW -> EditorScreenLang.text(EditorScreenLang.TILE_NEW);
            case NEW_SUB -> EditorScreenLang.text(EditorScreenLang.TILE_NEW_SUB_VARIANT);
            default -> null;
        };
    }

    /** A builder's upload: what it is called, what kind it is, and where it stands with a reviewer. */
    private static String tooltipFor(BuilderProfilePacket.Entry entry) {
        return EditorCreatorBuilds.label(entry)
            + "  ·  " + EditorScreenLang.text(EditorCreatorBuilds.kindKey(entry.kind()))
            + "  ·  " + EditorScreenLang.text(EditorCreatorBuilds.reviewKey(entry.review()));
    }

    private static String tooltipFor(EditorRosterIndex.Tile tile, boolean asSelf) {
        StringBuilder sb = new StringBuilder(asSelf
            ? EditorScreenLang.text(EditorScreenLang.TILE_SELF, tile.variant().name()) : tile.variant().name());
        int weight = asSelf ? tile.selfWeight() : tile.variant().weight();
        if (weight >= 0) sb.append("  ·  ").append(EditorScreenLang.text(EditorScreenLang.WEIGHT_READ_ONLY, weight));
        if (!asSelf && tile.isGroup()) {
            sb.append("  ·  ").append(EditorScreenLang.text(EditorScreenLang.SHEET_SHARE, tile.variant().subVariants().size()));
        }
        sb.append("  ·  ").append(TemplateDataSheet.sourceLabel(EditorRosterIndex.provenanceOf(tile.variant())));
        return sb.toString();
    }

    public Hit hitTest(double mx, double my) {
        if (filterRect != null && filterRect.contains(mx, my)) {
            for (int i = 0; i < chips.size(); i++) {
                Chip c = chips.get(i);
                if (mx >= c.x() && mx < c.x() + c.w()) return new Hit(HitKind.CHIP, i);
            }
            return Hit.NONE;
        }
        if (stripRect != null && stripRect.contains(mx, my)) {
            for (int i = 0; i < stripCells.size(); i++) {
                StripCell c = stripCells.get(i);
                if (mx >= c.x() && mx < c.x() + c.w()) return new Hit(HitKind.STRIP, i);
            }
            return Hit.NONE;
        }
        if (gridRect == null || !gridRect.contains(mx, my) || mainGrid == null) return Hit.NONE;
        if (creatorMode) {
            int c = mainGrid.indexAt(mx, my, scroll, creatorTiles.size());
            return c >= 0 && c < creatorTiles.size() ? new Hit(HitKind.CREATOR_TILE, c) : Hit.NONE;
        }
        int mainCount = tiles.size() + 1;
        int m = mainGrid.indexAt(mx, my, scroll, mainCount);
        if (m >= 0) return m < tiles.size() ? new Hit(HitKind.TILE, m) : new Hit(HitKind.NEW, -1);
        if (subParent != null) {
            int subCount = subTiles.size() + 2;
            int s = subGrid.indexAt(mx, my, scroll, subCount);
            if (s == 0) return new Hit(HitKind.SUB_TILE, -1);
            if (s > 0 && s <= subTiles.size()) return new Hit(HitKind.SUB_TILE, s - 1);
            if (s == subTiles.size() + 1) return new Hit(HitKind.NEW_SUB, -1);
        }
        return Hit.NONE;
    }

    /** Colours shared with the row painter, aliased so the pane reads without the long name. */
    private static final class MenuRowPainterColours {
        static final int HEADER = games.brennan.dungeontrain.client.menu.MenuRowPainter.TEXT_HEADER;
    }
}
