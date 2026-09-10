package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.builder.BuilderProfileFilters;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorRosterPacket;

import java.util.HashSet;
import java.util.Set;

/**
 * What the inventory-style editor screen remembers between openings.
 *
 * <p>Static on purpose, like the old menu's active tab: closing and reopening the screen should
 * land where the author left it. The one thing that is never remembered is the plot the player
 * stands in — that is server truth, re-read from the status HUD on every frame.</p>
 */
public final class EditorScreenState {

    private static EditorScreenPage page = EditorScreenPage.TEMPLATES;
    /** The category strip's cell. Carriages to start with: the grid the old first tab opened on. */
    private static EditorCategoryFilter category = EditorCategoryFilter.CARRIAGES;
    private static String typeName = "";
    private static EditorRosterIndex.Filters filters = EditorRosterIndex.Filters.DEFAULT;
    private static String text = "";
    private static VariantKey selection;
    /** The Layout tab's folded sections, by {@code EditorLayoutPage.sectionId}. Replaced, never mutated. */
    private static Set<String> collapsedSections = Set.of();
    /** The Layout tab's folded groups — parents whose members are hidden — by the parent's key string. */
    private static Set<String> collapsedGroups = Set.of();
    /** Whether the filter bar shows every filter, or only the ones in force on its one row. */
    private static boolean filtersExpanded;

    /**
     * The two narrowings creator mode adds: where a build stands with a reviewer, and whether it is
     * starred. Kept beside the roster's own filters and for the same reason — closing the screen mid
     * review and reopening it should land where the reviewer left off.
     *
     * <p>Not applied to the roster: a template on this machine has neither a review state nor a star,
     * so these two would narrow every local tile away to nothing.</p>
     */
    private static String creatorReview = BuilderProfileFilters.ALL;
    private static boolean creatorStarred;

    /**
     * Set on the way into the screen, cleared the first frame the roster can answer it.
     *
     * <p>Opening the menu while standing in a plot is a question about that plot far more often than
     * it is about the last one looked at, so the selection follows the author's feet. Deferred rather
     * than done at open() because the roster is fetched from the server and is usually not here yet —
     * and consumed once, so a resize (which re-inits the screen) does not drag the selection back.</p>
     */
    private static boolean selectStandingPending;

    private EditorScreenState() {}

    public static EditorScreenPage page() { return page; }
    public static EditorCategoryFilter category() { return category; }
    public static String typeName() { return typeName; }
    public static EditorRosterIndex.Filters filters() { return filters; }
    public static String text() { return text; }
    public static VariantKey selection() { return selection; }
    public static Set<String> collapsedSections() { return collapsedSections; }
    public static Set<String> collapsedGroups() { return collapsedGroups; }
    public static boolean filtersExpanded() { return filtersExpanded; }

    public static void toggleFilters() {
        filtersExpanded = !filtersExpanded;
    }
    public static String creatorReview() { return creatorReview; }
    public static boolean creatorStarred() { return creatorStarred; }

    public static void setCreatorReview(String next) {
        creatorReview = next == null ? BuilderProfileFilters.ALL : next;
    }

    public static void setCreatorStarred(boolean next) {
        creatorStarred = next;
    }

    /** Change tab. The type strip is left alone: it belongs to the category, not the page. */
    public static void setPage(EditorScreenPage next) {
        if (next == null || next == page) return;
        page = next;
    }

    /** Change category cell; the remembered type strip goes with the old one. */
    public static void setCategory(EditorCategoryFilter next) {
        if (next == null || next == category) return;
        category = next;
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

    /** Fold or unfold a group's members in the Layout tab. A new set each time, as for sections. */
    public static void toggleGroup(String groupId) {
        if (groupId == null) return;
        Set<String> next = new HashSet<>(collapsedGroups);
        if (!next.remove(groupId)) next.add(groupId);
        collapsedGroups = Set.copyOf(next);
    }

    /** Fold or unfold a Layout section. A new set each time, so a cache keyed by identity refreshes. */
    public static void toggleSection(String sectionId) {
        if (sectionId == null) return;
        Set<String> next = new HashSet<>(collapsedSections);
        if (!next.remove(sectionId)) next.add(sectionId);
        collapsedSections = Set.copyOf(next);
    }

    /** The plot the player stands in right now, or null outside a plot. */
    public static VariantKey standingIn() {
        return VariantKey.fromStatus(EditorStatusHudOverlay.category(),
            EditorStatusHudOverlay.modelId(), EditorStatusHudOverlay.modelName());
    }

    /** Ask that the next roster the screen sees selects the plot the author is standing in. */
    public static void requestStandingSelection() {
        selectStandingPending = true;
    }

    /**
     * Show the plot the player stands in: select it and jump the browser to its category and type
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
        browseTo(selection, group);
        filters = EditorRosterIndex.Filters.NONE;
        text = "";
    }

    /** Put the browser on the Templates tab, at the category and type strip a key lives under. */
    private static void browseTo(VariantKey key, EditorRosterPacket.Group group) {
        EditorCategoryFilter target = EditorCategoryFilter.forCategory(key.category());
        if (target != null) category = target;
        page = EditorScreenPage.TEMPLATES;
        typeName = group != null ? group.typeName() : "";
    }

    /** The roster group whose tiles the browser shows, by the remembered type or the category's first. */
    public static String effectiveTypeName(EditorRosterIndex index) {
        PlotCategory cat = category.category();
        if (cat == null) return "";
        if (!typeName.isEmpty() && !index.tiles(cat, typeName).isEmpty()) return typeName;
        EditorRosterIndex.TypeStrip first = index.firstStrip(cat);
        return first == null ? "" : first.typeName();
    }

    /** Drop the selection when the roster no longer knows it (removed, renamed, other world). */
    public static void reconcile(EditorRosterIndex index) {
        if (index.isEmpty()) return;
        if (selectStandingPending) {
            // Once the roster is here the question is answerable either way: standing in a template
            // selects it, standing nowhere leaves whatever was selected before.
            selectStandingPending = false;
            VariantKey here = standingIn();
            if (here != null) {
                selection = index.find(here) != null ? index.find(here).key() : here;
                // The category and strip move to it, but the FILTERS stay: the browser now keeps
                // the standing template at the front of the grid however they are set, so clearing
                // them would throw away the author's narrowing to solve a problem it no longer has.
                browseTo(selection, index.groupOf(selection));
            }
        }
        if (selection == null || index.find(selection) == null) {
            VariantKey here = standingIn();
            selection = here != null && index.find(here) != null ? index.find(here).key() : null;
        }
    }
}
