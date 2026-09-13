package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.builder.BuilderProfileFilters;
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
 * The left pane's grid: the tiles of the chosen category and type, and the sub-variant grid that
 * opens under a selected group. The rows above it — search, chips, strips — are
 * {@link EditorFilterBar}, shared with the Layout tab.
 *
 * <p>Everything scrolls together inside the grid rect. Hit-testing reads back the geometry of
 * the last frame, so what was drawn is what a click lands on.</p>
 */
public final class EditorBrowserPane {

    static final int TILE_GAP = 3;
    static final int SUB_HEADER_H = 12;
    static final int SUB_GAP = 6;
    /** The star on a relay tile: the square in its top-left corner, and how far in it sits. */
    static final int STAR_SIZE = 9;
    static final int STAR_INSET = 1;
    static final int STAR_ON = 0xFFFFDD55;
    static final int STAR_OFF = 0xFFB0B8C0;
    static final int SUB_HEADER_BG = 0xD0000000;
    static final int CELL_HOVER = EditorFilterBar.CELL_HOVER;

    /** What a click landed on. */
    public enum HitKind { NONE, TILE, NEW, SUB_TILE, NEW_SUB, CREATOR_TILE, CREATOR_STAR }

    public record Hit(HitKind kind, int index) {
        public static final Hit NONE = new Hit(HitKind.NONE, -1);
    }



    private final BuilderTileSpin spin = new BuilderTileSpin();

    private int scroll;
    private List<EditorRosterIndex.Tile> tiles = List.of();
    /** True when {@link #tiles}' first entry survived only because the author stands in it. */
    private boolean ghostFirst;
    private List<EditorRosterIndex.Tile> subTiles = List.of();
    /** The loaded builder's uploads, drawn in the main grid in place of the roster's tiles. */
    private List<BuilderProfilePacket.Entry> creatorTiles = List.of();
    private boolean creatorMode;
    private EditorRosterIndex.Tile subParent;
    private TemplateTileGridLayout mainGrid;
    private TemplateTileGridLayout subGrid;
    private InventoryEditorLayout.Rect gridRect;
    private int contentHeight;
    private Hit hovered = Hit.NONE;

    public List<EditorRosterIndex.Tile> tiles() { return tiles; }
    public List<BuilderProfilePacket.Entry> creatorTiles() { return creatorTiles; }
    public List<EditorRosterIndex.Tile> subTiles() { return subTiles; }
    public EditorRosterIndex.Tile subParent() { return subParent; }
    public Hit hovered() { return hovered; }

    /** Lay the pane out for this frame from the roster and the remembered state. */
    public void layout(InventoryEditorLayout layout, Font font, EditorRosterIndex index) {
        gridRect = layout.grid();
        creatorMode = EditorCreatorBuilds.active();
        // The category cell and type strip the filter bar chose; All is the roster in one grid.
        PlotCategory page = EditorScreenState.category().category();

        // Somebody else's uploads take the main grid whole: they are not roster tiles, have no
        // type strip to sit under and no sub-variants to open, so every other list goes empty for
        // the duration rather than being drawn against a roster this grid is not showing.
        creatorTiles = creatorMode
            ? EditorCreatorBuilds.forCategory(EditorScreenState.category(), EditorScreenState.text(),
                EditorScreenState.creatorReview(), EditorScreenState.creatorStarred())
            : List.of();

        // Tiles of the active strip, filtered.
        String typeName = EditorScreenState.effectiveTypeName(index);
        List<EditorRosterIndex.Tile> all = creatorMode ? List.of()
            : (page == null ? index.allTiles() : index.tiles(page, typeName));
        // The plot under the author's feet is never filtered away — see EditorRosterIndex.standingFirst.
        EditorRosterIndex.Shown shown = EditorRosterIndex.standingFirst(
            EditorRosterIndex.filter(all, EditorScreenState.filters(), EditorScreenState.text()),
            all, EditorScreenState.standingIn());
        tiles = shown.tiles();
        ghostFirst = shown.firstIsGhost();

        // The sub-variant grid, when the selection is a group or a member of one.
        subParent = null;
        subTiles = List.of();
        VariantKey sel = creatorMode ? null : EditorScreenState.selection();
        if (sel != null) {
            EditorRosterIndex.Tile selTile = index.find(sel);
            EditorRosterIndex.Tile parent = selTile == null ? null
                : (selTile.key().isSubVariant() ? index.parentOf(selTile.key()) : (selTile.isGroup() ? selTile : null));
            // Under All there is no type strip to agree with — every category is in one grid — so
            // a group opens wherever it is shown. Elsewhere it opens only under its own strip, or a
            // click would open sub-variants beneath a row that does not hold them.
            boolean underItsStrip = page == null
                || (parent != null && index.groupOf(parent.key()) != null
                    && index.groupOf(parent.key()).typeName().equals(typeName));
            if (parent != null && underItsStrip) {
                subParent = parent;
                // The provenance chips are not applied here. They narrow what you are browsing;
                // opening a group is asking for what is inside this one, and answering with three
                // of its eleven rooms — because the rest are built-in and that chip is off — reads
                // as a group that lost its members. The name filter still applies: it is a search.
                subTiles = EditorRosterIndex.standingFirst(
                    EditorRosterIndex.subVariants(parent, EditorRosterIndex.Filters.NONE,
                        EditorScreenState.text()),
                    EditorScreenState.standingIn());
            }
        }

        int tile = layout.tile();
        mainGrid = TemplateTileGridLayout.of(gridRect.x(), gridRect.y(), gridRect.w(), gridRect.h(), tile, TILE_GAP);
        // No "+" cell in creator mode: there is nothing here this editor can add to.
        int mainCount = creatorMode ? creatorTiles.size() : tiles.size() + 1;
        int subTop = gridRect.y() + mainGrid.contentHeight(mainCount) + SUB_GAP + SUB_HEADER_H + 2;
        // Laid out from subTop, seen through the panel: a member scrolled up past subTop is still
        // on screen, and before this it stopped being drawn and stopped answering clicks there.
        subGrid = TemplateTileGridLayout.of(gridRect.x(), subTop, gridRect.w(), gridRect.h(), tile, TILE_GAP)
            .withViewport(gridRect.y(), gridRect.h());
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

        // Grids, scissored to the grid rect.
        g.enableScissor(gridRect.x(), gridRect.y(), gridRect.right(), gridRect.bottom());
        VariantKey selection = EditorScreenState.selection();
        VariantKey standing = EditorScreenState.standingIn();
        if (creatorMode) {
            drawCreatorTiles(g, font, seconds);
            g.disableScissor();
            return;
        }
        drawTiles(g, font, mainGrid, tiles, selection, standing, seconds, HitKind.TILE, HitKind.NEW, null,
            ghostFirst);
        if (subParent != null) {
            int headerY = gridRect.y() + mainGrid.contentHeight(tiles.size() + 1) + SUB_GAP - scroll;
            g.fill(gridRect.x(), headerY, gridRect.right(), headerY + SUB_HEADER_H, SUB_HEADER_BG);
            String header = EditorScreenLang.text(EditorScreenLang.SUB_VARIANTS_OF, subParent.key().displayName());
            g.drawString(font, font.plainSubstrByWidth(header, gridRect.w() - 6), gridRect.x() + 3,
                headerY + (SUB_HEADER_H - font.lineHeight) / 2 + 1, MenuRowPainterColours.HEADER, false);
            drawTiles(g, font, subGrid, subTiles, selection, standing, seconds, HitKind.SUB_TILE,
                HitKind.NEW_SUB, subParent, false);
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
                new TemplateTilePainter.Marks(selected, hov, false, false, false, false), entry.relayId());
            int border = BuilderReviewState.borderColourFor(entry.review());
            if (border != BuilderReviewState.BORDER_NONE) g.renderOutline(x, y, size, size, border);
            // The star sits in the corner a local tile keeps for its unsaved mark, which a relay
            // build never has. Drawn last so it is never under the review ring.
            boolean starred = EditorCreatorBuilds.starred(entry);
            boolean hovStar = hovered.kind() == HitKind.CREATOR_STAR && hovered.index() == i;
            String star = starred ? "\u2605" : "\u2606";
            g.fill(x + STAR_INSET, y + STAR_INSET, x + STAR_INSET + STAR_SIZE,
                y + STAR_INSET + STAR_SIZE, hovStar ? CELL_HOVER : SUB_HEADER_BG);
            g.drawString(font, star, x + STAR_INSET + (STAR_SIZE - font.width(star)) / 2 + 1,
                y + STAR_INSET + 1, hovStar ? 0xFF000000 : starred ? STAR_ON : STAR_OFF, false);
        }
    }

    /** One grid: a self tile first when it is a sub-variant grid, then the tiles, then "+". */
    private void drawTiles(GuiGraphics g, Font font, TemplateTileGridLayout grid,
                           List<EditorRosterIndex.Tile> list, VariantKey selection, VariantKey standing,
                           float seconds, HitKind tileKind, HitKind newKind, EditorRosterIndex.Tile self,
                           boolean firstIsGhost) {
        int offset = self == null ? 0 : 1;
        int count = list.size() + offset;
        if (self != null && grid.isVisible(0, scroll)) {
            drawTile(g, font, self, true, grid.xFor(0), grid.yFor(0, scroll), grid.tile(), selection, standing, seconds,
                hovered.kind() == tileKind && hovered.index() == -1, false);
        }
        for (int i = 0; i < list.size(); i++) {
            int slot = i + offset;
            if (!grid.isVisible(slot, scroll)) continue;
            drawTile(g, font, list.get(i), false, grid.xFor(slot), grid.yFor(slot, scroll), grid.tile(),
                selection, standing, seconds, hovered.kind() == tileKind && hovered.index() == i,
                firstIsGhost && i == 0);
        }
        if (grid.isVisible(count, scroll)) {
            TemplateTilePainter.drawNew(g, font, grid.xFor(count), grid.yFor(count, scroll), grid.tile(),
                hovered.kind() == newKind);
        }
    }

    private void drawTile(GuiGraphics g, Font font, EditorRosterIndex.Tile tile, boolean asSelf,
                          int x, int y, int size, VariantKey selection, VariantKey standing, float seconds,
                          boolean hov, boolean ghost) {
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
        String name = asSelf ? EditorScreenLang.text(EditorScreenLang.TILE_SELF, v.displayName()) : v.displayName();
        TemplateTilePainter.draw(g, font, art, name, weight, x, y, size, yaw,
            new TemplateTilePainter.Marks(selected, hov, here, dirty, !asSelf && tile.isGroup(), ghost));
    }

    /** Whether the point is on tile {@code i}'s star rather than the picture behind it. */
    private boolean overStar(int i, double mx, double my) {
        int x = mainGrid.xFor(i) + STAR_INSET;
        int y = mainGrid.yFor(i, scroll) + STAR_INSET;
        return mx >= x && mx < x + STAR_SIZE && my >= y && my < y + STAR_SIZE;
    }

    /** The tile under the point, for the tooltip; null over nothing. */
    public String tooltipAt(Hit hit) {
        return switch (hit.kind()) {
            case TILE -> hit.index() >= 0 && hit.index() < tiles.size() ? tooltipFor(tiles.get(hit.index()), false) : null;
            case SUB_TILE -> hit.index() == -1 && subParent != null ? tooltipFor(subParent, true)
                : hit.index() >= 0 && hit.index() < subTiles.size() ? tooltipFor(subTiles.get(hit.index()), false) : null;
            case CREATOR_TILE, CREATOR_STAR -> hit.index() >= 0 && hit.index() < creatorTiles.size()
                ? tooltipFor(creatorTiles.get(hit.index())) : null;
            case NEW -> EditorScreenLang.text(EditorScreenLang.TILE_NEW);
            case NEW_SUB -> EditorScreenLang.text(EditorScreenLang.TILE_NEW_SUB_VARIANT);
            default -> null;
        };
    }

    /** A builder's upload: what it is called, what kind it is, and where it stands with a reviewer. */
    private static String tooltipFor(BuilderProfilePacket.Entry entry) {
        StringBuilder sb = new StringBuilder(EditorCreatorBuilds.label(entry));
        // Whose it is, but only where the grid spans owners: on one builder's profile the chip above
        // already says the name, and repeating it on every tile is noise.
        if (EditorCreatorBuilds.pooled() && !entry.ownerName().isEmpty()) {
            sb.append("  ·  ").append(EditorScreenLang.text(EditorScreenLang.CREATOR_BY))
                .append(' ').append(entry.ownerName());
        }
        return sb.append("  ·  ").append(EditorScreenLang.text(EditorCreatorBuilds.kindKey(entry.kind())))
            .append("  ·  ").append(EditorScreenLang.text(EditorCreatorBuilds.reviewKey(entry.review())))
            .toString();
    }

    private static String tooltipFor(EditorRosterIndex.Tile tile, boolean asSelf) {
        StringBuilder sb = new StringBuilder(asSelf
            ? EditorScreenLang.text(EditorScreenLang.TILE_SELF, tile.variant().displayName()) : tile.variant().displayName());
        // A labelled tile keeps its id in reach — it is what every command and file is named by.
        if (tile.variant().isLabelled()) sb.append("  ·  ").append(tile.variant().name());
        int weight = asSelf ? tile.selfWeight() : tile.variant().weight();
        if (weight >= 0) sb.append("  ·  ").append(EditorScreenLang.text(EditorScreenLang.WEIGHT_READ_ONLY, weight));
        if (!asSelf && tile.isGroup()) {
            sb.append("  ·  ").append(EditorScreenLang.text(EditorScreenLang.SHEET_SHARE, tile.variant().subVariants().size()));
        }
        sb.append("  ·  ").append(TemplateDataSheet.sourceLabel(EditorRosterIndex.provenanceOf(tile.variant())));
        return sb.toString();
    }

    public Hit hitTest(double mx, double my) {
        if (gridRect == null || !gridRect.contains(mx, my) || mainGrid == null) return Hit.NONE;
        if (creatorMode) {
            int c = mainGrid.indexAt(mx, my, scroll, creatorTiles.size());
            if (c < 0 || c >= creatorTiles.size()) return Hit.NONE;
            // The star is tested BEFORE the cell it sits inside: the other order means the cell
            // swallows the click and the star can never be pressed at all.
            return overStar(c, mx, my) ? new Hit(HitKind.CREATOR_STAR, c) : new Hit(HitKind.CREATOR_TILE, c);
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
