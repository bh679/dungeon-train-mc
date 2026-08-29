package games.brennan.dungeontrain.client.menu;

import java.util.List;

/**
 * The four groups the editor menu's rows are filed under, and which one the player is
 * currently looking at.
 *
 * <p>The panel had grown past thirty rows in a portal-room plot with everything in one
 * list — destructive file actions next to geometry steppers next to editor preferences.
 * The split is by <em>what the row acts on</em>:</p>
 *
 * <ul>
 *   <li>{@link #FILE} — the template as a file: create, save, rename, destroy, package.</li>
 *   <li>{@link #CURRENT} — properties of the model the player is standing in: geometry,
 *       contents, and the spawn gate.</li>
 *   <li>{@link #SETTINGS} — editor-wide preferences and mirroring, which outlive any one
 *       model.</li>
 *   <li>{@link #NAV} — getting into a category, into the room, and back out.</li>
 * </ul>
 *
 * <p>The selection is client-only state — the server has no interest in which tab is
 * showing — so it lives here as a static, the same shape as
 * {@link ClientPartVisibility} in this package. It deliberately persists across closing
 * and reopening the menu: an author working through a room's geometry should not be sent
 * back to File every time they press {@code X}.</p>
 */
public enum EditorMenuTab {

    FILE("File"),
    CURRENT("Current"),
    SETTINGS("Settings"),
    NAV("Nav");

    private final String label;

    EditorMenuTab(String label) {
        this.label = label;
    }

    /** Text shown on this tab's cell in the strip. */
    public String label() {
        return label;
    }

    /**
     * Opens on Current: it is the tab an author spends the most time in, and the one whose
     * rows change as they build. Categories without any Current rows fall back through
     * {@link #resolve}.
     */
    private static EditorMenuTab active = CURRENT;

    /** The tab the player last chose. May not be visible in the current category — see {@link #resolve}. */
    public static EditorMenuTab active() {
        return active;
    }

    public static void select(EditorMenuTab tab) {
        if (tab != null) active = tab;
    }

    /**
     * The tab to actually show, given which ones have rows in this category.
     *
     * <p>Empty tabs are hidden rather than shown blank, so the strip in an
     * {@code architecture} plot is File | Settings | Nav. That means the remembered tab can
     * point at something not on screen — walk from a portal room into an architecture plot
     * while on Current and it would render an empty panel. Falling back to the first visible
     * tab keeps the panel populated without clobbering the remembered choice, so stepping
     * back into a portal room returns the player to Current.</p>
     *
     * @param visible tabs that have at least one row, in strip order; never empty in practice
     *                because File always has rows.
     */
    public static EditorMenuTab resolve(List<EditorMenuTab> visible) {
        if (visible.isEmpty()) return active;
        return visible.contains(active) ? active : visible.get(0);
    }
}
