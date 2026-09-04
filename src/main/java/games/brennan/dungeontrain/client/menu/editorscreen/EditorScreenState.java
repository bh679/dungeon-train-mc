package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.editor.PlotCategory;

/**
 * What the inventory-style editor screen remembers between openings.
 *
 * <p>Static on purpose, like the old menu's active tab: closing and reopening the screen should
 * land where the author left it. The one thing that is never remembered is the plot the player
 * stands in — that is server truth, re-read from the status HUD on every frame.</p>
 */
public final class EditorScreenState {

    private static EditorScreenPage page = EditorScreenPage.CARRIAGES;
    private static String typeName = "";
    private static EditorRosterIndex.Filters filters = EditorRosterIndex.Filters.NONE;
    private static String text = "";
    private static VariantKey selection;

    private EditorScreenState() {}

    public static EditorScreenPage page() { return page; }
    public static String typeName() { return typeName; }
    public static EditorRosterIndex.Filters filters() { return filters; }
    public static String text() { return text; }
    public static VariantKey selection() { return selection; }

    public static void setPage(EditorScreenPage next) {
        if (next == null || next == page) return;
        page = next;
        typeName = "";
    }

    public static void setTypeName(String next) {
        typeName = next == null ? "" : next;
    }

    public static void setFilters(EditorRosterIndex.Filters next) {
        filters = next == null ? EditorRosterIndex.Filters.NONE : next;
    }

    public static void setText(String next) {
        text = next == null ? "" : next;
    }

    public static void select(VariantKey key) {
        selection = key;
    }

    /** The plot the player stands in right now, or null outside a plot. */
    public static VariantKey standingIn() {
        return VariantKey.fromStatus(EditorStatusHudOverlay.category(),
            EditorStatusHudOverlay.modelId(), EditorStatusHudOverlay.modelName());
    }

    /**
     * Show the plot the player stands in: select it and jump the browser to its page and type
     * strip, clearing the filters so its tile is certainly visible. The Current tab, in effect.
     */
    public static void showStandingIn(EditorRosterIndex index) {
        VariantKey here = standingIn();
        if (here == null) return;
        selection = index.find(here) != null ? index.find(here).key() : here;
        revealSelection(index);
    }

    /** Move the browser to wherever the selection lives, without touching the selection. */
    public static void revealSelection(EditorRosterIndex index) {
        if (selection == null) return;
        var group = index.groupOf(selection);
        EditorScreenPage target = EditorScreenPage.forCategory(selection.category());
        if (target != null) page = target;
        typeName = group != null ? group.typeName() : "";
        filters = EditorRosterIndex.Filters.NONE;
        text = "";
    }

    /** The roster group whose tiles the browser shows, by the remembered type or the page's first. */
    public static String effectiveTypeName(EditorRosterIndex index) {
        PlotCategory category = page.category();
        if (category == null) return "";
        if (!typeName.isEmpty() && !index.tiles(category, typeName).isEmpty()) return typeName;
        EditorRosterIndex.TypeStrip first = index.firstStrip(category);
        return first == null ? "" : first.typeName();
    }

    /** Drop the selection when the roster no longer knows it (removed, renamed, other world). */
    public static void reconcile(EditorRosterIndex index) {
        if (index.isEmpty()) return;
        if (selection == null || index.find(selection) == null) {
            VariantKey here = standingIn();
            selection = here != null && index.find(here) != null ? index.find(here).key() : null;
        }
    }
}
