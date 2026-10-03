package games.brennan.dungeontrain.client.menu.editorscreen;

/**
 * The top tabs of the inventory-style editor screen, in strip order.
 *
 * <p>Templates browses the roster — its category strip narrows it, see
 * {@link EditorCategoryFilter}. Layout is the spawn table: every type's variants with their weights,
 * stages and grouping in one list. Stages lists every Stage with the blocks its linked parts use.
 * Nav is the title-screen picker brought indoors: the four areas of the editor as tiles, each with
 * its description and a way to go there. Settings is the old menu's Settings rows.</p>
 */
public enum EditorScreenPage {
    TEMPLATES(EditorScreenLang.TAB_TEMPLATES),
    LAYOUT(EditorScreenLang.TAB_LAYOUT),
    STAGES(EditorScreenLang.TAB_STAGES),
    NAV(EditorScreenLang.TAB_NAV),
    /** Tunnel template groups — shown only while the editor is on Tracks, see {@link EditorGroupsTab}. */
    GROUPS(EditorScreenLang.TAB_GROUPS),
    /**
     * The relay's builds awaiting review, from every builder, with one button that stages them all on
     * the Workbench. A browser page: the grid and filter bar are the Templates tab's, in creator mode.
     */
    WORKBENCH(EditorScreenLang.TAB_WORKBENCH),
    SETTINGS(EditorScreenLang.TAB_SETTINGS);

    private final String langKey;

    EditorScreenPage(String langKey) {
        this.langKey = langKey;
    }

    public String langKey() {
        return langKey;
    }

    /** Whether this page shows the tile browser — only Templates does. */
    public boolean isBrowser() {
        return this == TEMPLATES || this == WORKBENCH;
    }
}
