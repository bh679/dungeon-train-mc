package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.editor.PlotCategory;

/**
 * The top tabs of the inventory-style editor screen, in strip order.
 *
 * <p>Four browse the roster by category; two are pages of their own. {@code DIMENSIONS} is the
 * player-facing name of the Portals category — the data key underneath is unchanged.</p>
 */
public enum EditorScreenPage {
    /** Every template in the editor, from every category, in one grid. */
    ALL(null, EditorScreenLang.TAB_ALL),
    CARRIAGES(PlotCategory.CARRIAGES, EditorScreenLang.TAB_CARRIAGES),
    CONTENTS(PlotCategory.CONTENTS, EditorScreenLang.TAB_CONTENTS),
    TRACKS(PlotCategory.TRACKS, EditorScreenLang.TAB_TRACKS),
    DIMENSIONS(PlotCategory.PORTALS, EditorScreenLang.TAB_DIMENSIONS),
    SETTINGS(null, EditorScreenLang.TAB_SETTINGS);

    private final PlotCategory category;
    private final String langKey;

    EditorScreenPage(PlotCategory category, String langKey) {
        this.category = category;
        this.langKey = langKey;
    }

    /** The one category this page browses, or null for All and for Settings. */
    public PlotCategory category() {
        return category;
    }

    public String langKey() {
        return langKey;
    }

    /** Whether this page shows the tile browser. All has no category of its own but is one. */
    public boolean isBrowser() {
        return category != null || this == ALL;
    }

    /** The browse page for a category; parts browse under Carriages. Null for architecture. */
    public static EditorScreenPage forCategory(PlotCategory category) {
        if (category == null) return null;
        return switch (category) {
            case CARRIAGES, PARTS -> CARRIAGES;
            case CONTENTS -> CONTENTS;
            case TRACKS -> TRACKS;
            case PORTALS -> DIMENSIONS;
            case ARCHITECTURE -> null;
        };
    }
}
