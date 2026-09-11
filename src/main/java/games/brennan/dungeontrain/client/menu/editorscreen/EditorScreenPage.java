package games.brennan.dungeontrain.client.menu.editorscreen;

/**
 * The top tabs of the inventory-style editor screen, in strip order.
 *
 * <p>Templates browses the roster — its category strip narrows it, see
 * {@link EditorCategoryFilter}. Layout is the spawn table: every type's variants with their weights,
 * stages and grouping in one list. Settings is the old menu's Settings rows.</p>
 */
public enum EditorScreenPage {
    TEMPLATES(EditorScreenLang.TAB_TEMPLATES),
    LAYOUT(EditorScreenLang.TAB_LAYOUT),
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
        return this == TEMPLATES;
    }
}
