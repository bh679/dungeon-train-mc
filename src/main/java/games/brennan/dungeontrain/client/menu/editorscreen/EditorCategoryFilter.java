package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.editor.PlotCategory;

/**
 * The category strip of the Templates tab, in strip order: everything, then one cell per category.
 *
 * <p>This is a filter, not a page. It keeps the four rules a bare {@link PlotCategory} could not:
 * the {@code All} sentinel, the strip order, the player-facing name of each cell — {@code DIMENSIONS}
 * is what the Portals category is called on screen; its data key is unchanged — and the fold that
 * browses parts under Carriages.</p>
 */
public enum EditorCategoryFilter {
    /** Every template in the editor, from every category, in one grid. */
    ALL(null, EditorScreenLang.TAB_ALL),
    CARRIAGES(PlotCategory.CARRIAGES, EditorScreenLang.TAB_CARRIAGES),
    CONTENTS(PlotCategory.CONTENTS, EditorScreenLang.TAB_CONTENTS),
    TRACKS(PlotCategory.TRACKS, EditorScreenLang.TAB_TRACKS),
    DIMENSIONS(PlotCategory.PORTALS, EditorScreenLang.TAB_DIMENSIONS);

    private final PlotCategory category;
    private final String langKey;

    EditorCategoryFilter(PlotCategory category, String langKey) {
        this.category = category;
        this.langKey = langKey;
    }

    /** The one category this cell browses, or null for All. */
    public PlotCategory category() {
        return category;
    }

    public String langKey() {
        return langKey;
    }

    /** The cell a category's templates browse under; parts browse under Carriages. Null for architecture. */
    public static EditorCategoryFilter forCategory(PlotCategory category) {
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
