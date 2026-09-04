package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.client.builder.BuilderProfileState;
import games.brennan.dungeontrain.client.builder.BuilderTileSpin;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * The My Builds page: what this player has uploaded to their relay profile, as tiles, inside the
 * editor screen rather than in a screen of its own.
 *
 * <p>Reads {@link BuilderProfileState}, which the builder profile screen already fills, and draws
 * each build through the same {@link TemplateArt} path as an editor template — a relay build names
 * its store the same way, so the models and photos come out identical.</p>
 *
 * <p>Read-only. Publishing, withdrawing and downloading a build are their own flow with their own
 * confirmations, and they live in the builder profile screen; what belongs here is seeing what you
 * have made while you are in the editor.</p>
 */
public final class EditorMyBuildsPane {

    static final int TILE_GAP = 3;
    static final int HEADER_H = 12;

    private final BuilderTileSpin spin = new BuilderTileSpin();

    private List<BuilderProfilePacket.Entry> builds = List.of();
    private InventoryEditorLayout.Rect rect;
    private TemplateTileGridLayout grid;
    private int scroll;
    private int hovered = TemplateTileGridLayout.NONE;
    private int selected = -1;

    /** The build the right pane describes, or null. */
    public BuilderProfilePacket.Entry selectedBuild() {
        return selected >= 0 && selected < builds.size() ? builds.get(selected) : null;
    }

    public int hovered() {
        return hovered;
    }

    public void select(int index) {
        selected = index >= 0 && index < builds.size() ? index : -1;
    }

    public void resetScroll() {
        scroll = 0;
    }

    public boolean overGrid(double mx, double my) {
        return rect != null && rect.contains(mx, my);
    }

    public boolean scrollBy(int dir) {
        if (grid == null) return false;
        int max = grid.maxScroll(builds.size());
        int next = Math.max(0, Math.min(scroll + dir * 24, max));
        boolean moved = next != scroll;
        scroll = next;
        return moved || max > 0;
    }

    /** Take up the whole browser column — this page has no filter row or type strip. */
    public void layout(InventoryEditorLayout layout) {
        rect = new InventoryEditorLayout.Rect(layout.filter().x(), layout.filter().y(),
            layout.filter().w(), layout.grid().bottom() - layout.filter().y());
        builds = BuilderProfileState.builds();
        if (selected >= builds.size()) selected = -1;
        grid = TemplateTileGridLayout.of(rect.x(), rect.y() + HEADER_H, rect.w(),
            Math.max(0, rect.h() - HEADER_H), layout.tile(), TILE_GAP);
        scroll = grid.clampScroll(scroll, builds.size());
    }

    public void render(GuiGraphics g, Font font, float seconds, int mouseX, int mouseY) {
        hovered = hitTest(mouseX, mouseY);

        String message = message();
        if (message != null) {
            g.drawString(font, font.plainSubstrByWidth(message, rect.w() - 4), rect.x() + 2,
                rect.y() + 2, MenuRowPainter.TEXT_NORMAL, false);
            return;
        }

        g.drawString(font, header(), rect.x() + 2, rect.y() + 2, MenuRowPainter.TEXT_HEADER, false);
        g.enableScissor(rect.x(), rect.y() + HEADER_H, rect.right(), rect.bottom());
        for (int i = 0; i < builds.size(); i++) {
            if (!grid.isVisible(i, scroll)) continue;
            BuilderProfilePacket.Entry entry = builds.get(i);
            TemplateArt art = TemplateArt.ofBuild(entry);
            float yaw = spin.advance(art == null ? entry.buildName() : art.spinKey(), hovered == i, seconds);
            // A relay build has no spawn weight and no plot to stand in, so the weight badge and
            // the here-dot are left off. The orange dot keeps its meaning — edits not yet uploaded,
            // rather than not yet saved. Favourite and group are not marked: the group glyph means
            // "has sub-variants", and borrowing it for a starred build would say something false.
            TemplateTilePainter.draw(g, font, art, entry.buildName(), EditorPlotLabelsPacket.NO_WEIGHT,
                grid.xFor(i), grid.yFor(i, scroll), grid.tile(), yaw,
                new TemplateTilePainter.Marks(i == selected, hovered == i, false,
                    entry.changes() > 0, false));
        }
        g.disableScissor();
    }

    /** The count line above the tiles. */
    private String header() {
        return EditorScreenLang.text(EditorScreenLang.TAB_MY_BUILDS) + "  ·  " + builds.size();
    }

    /**
     * Why there is nothing to show, or null when there is.
     *
     * <p>Every one of these strings is the builder profile screen's own, so this page says the same
     * thing that screen does about the same situation, in every language it already speaks.</p>
     */
    public static String message() {
        BuilderProfilePacket latest = BuilderProfileState.latest();
        if (latest == null) return EditorScreenLang.text("gui.dungeontrain.builder.profile.loading");
        return switch (latest.status()) {
            case OK -> latest.builds().isEmpty()
                ? EditorScreenLang.text("gui.dungeontrain.builder.profile.empty") : null;
            case UNAVAILABLE -> EditorScreenLang.text("gui.dungeontrain.builder.profile.unavailable");
            case DISABLED -> EditorScreenLang.text("gui.dungeontrain.builder.profile.disabled");
            case NO_CONSENT -> EditorScreenLang.text("gui.dungeontrain.builder.profile.no_consent");
            case CONSENT_PENDING -> EditorScreenLang.text("gui.dungeontrain.builder.profile.consent_pending");
        };
    }

    /** The tile under the point, or {@link TemplateTileGridLayout#NONE}. */
    public int hitTest(double mx, double my) {
        if (grid == null || message() != null) return TemplateTileGridLayout.NONE;
        return grid.indexAt(mx, my, scroll, builds.size());
    }

    /** The hovered build's name, for the tooltip. */
    public String tooltipAt(int index) {
        if (index < 0 || index >= builds.size()) return null;
        BuilderProfilePacket.Entry entry = builds.get(index);
        String tip = entry.buildName() + "  ·  " + typeLabel(entry) + "  ·  " + statusLabel(entry);
        return entry.favourite() ? tip + "  ·  ★" : tip;
    }

    /** The build's kind, in the builder profile's own words. */
    public static String typeLabel(BuilderProfilePacket.Entry entry) {
        return EditorScreenLang.text("gui.dungeontrain.builder.profile.type." + entry.kind());
    }

    /** Where the build stands with the reviewer, in the builder profile's own words. */
    public static String statusLabel(BuilderProfilePacket.Entry entry) {
        return EditorScreenLang.text(
            "gui.dungeontrain.builder.profile.status." + BuilderReviewState.of(entry.review()));
    }
}
