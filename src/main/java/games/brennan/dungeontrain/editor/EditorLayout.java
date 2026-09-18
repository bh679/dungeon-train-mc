package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriageDims;

/**
 * Shared layout constants for every editor (carriage, contents, parts,
 * track-side, portal rooms). The single {@link #GAP} value is the empty-block separation
 * between any two adjacent template footprints — same on every axis,
 * across every editor — so the world above {@link #PLOT_Y} reads as a
 * uniform grid.
 *
 * <p><b>One origin, one resident type.</b> Every editor category (CARRIAGES, CONTENTS, TRACKS,
 * PORTALS) lays its plots out from the same {@code (0, PLOT_Y, 0)} origin. Only one category is
 * ever stamped in the world at a time ({@link EditorStampedCategoryState}); switching category
 * erases the previous one first, and every {@code plotContaining} answers only for the resident
 * category. So the categories' footprints may — and do — overlap in plan view, and no category
 * reserves a Z range for another: a contents group can run as far along {@code +Z} as it has
 * members, and the track-side rows can hold as many named variants as an author makes.</p>
 *
 * <p>This replaced disjoint per-category Z lanes. Those lanes had to be sized by a guess at how
 * many sub-variants a group would ever hold, and the guess was wrong the day a contents group
 * outgrew it: its deepest members sat in the next lane, the status HUD called them by the other
 * category's name, and that category's stamps overlapped them.</p>
 *
 * <p>Layout (all at {@link #PLOT_Y}, in {@code +Z} order, <em>within</em> a category):
 * <ul>
 *   <li>WHOLE: {@code Z=0} room row (one carriage box each), then from
 *       {@link #WHOLE_GROUP_FIRST_Z} the group row — each plot {@code groupSize} carriages long</li>
 *   <li>CARRIAGES: {@code Z=0..MAX_WIDTH-1} carriage row, then from {@link #PARTS_FIRST_Z} the parts
 *       grid — FLOOR / WALLS / ROOF / DOORS rows</li>
 *   <li>CONTENTS: {@code Z=CONTENTS_FIRST_Z} row, each group's members stacked along {@code +Z}
 *       below their parent by {@link #SUB_VARIANT_GAP}</li>
 *   <li>TRACKS / PORTALS: from {@code Z=TRACKS_FIRST_Z}, one X column per kind, named variants
 *       stacked along {@code +Z} (see {@link TrackSidePlots})</li>
 * </ul></p>
 */
public final class EditorLayout {

    /**
     * Empty-block gap between adjacent template footprints. Outline cages
     * sit one block past each footprint edge, so two adjacent plots'
     * bedrock cages are separated by ({@code GAP - 2}) air blocks
     * (i.e. for {@code GAP = 5} → 3 visible air blocks between cages).
     */
    public static final int GAP = 5;

    /**
     * First Z slot of the parts grid inside the CARRIAGES view. Sits one {@link #GAP} past the
     * carriage row's max-width footprint — the same inter-plot spacing used everywhere else
     * ({@code GAP - 2 = 3} air blocks between the carriage cage and the first parts-row cage), so
     * the parts grid reads as a sibling of the carriage row rather than a distant block.
     */
    public static final int PARTS_FIRST_Z = CarriageDims.MAX_WIDTH + GAP;

    /** First Z slot of the room row (WHOLE view). The shared origin. */
    public static final int WHOLE_ROOM_FIRST_Z = 0;

    /**
     * First Z slot of the group row inside the WHOLE view — one {@link #GAP} past the room row's
     * max-width footprint, numerically the same band the parts grid uses in the CARRIAGES view.
     * Safe because only the resident category answers {@code plotContaining}; see the class doc.
     */
    public static final int WHOLE_GROUP_FIRST_Z = CarriageDims.MAX_WIDTH + GAP;

    /**
     * First Z slot of the contents row (CONTENTS view). The shared origin — see the class doc for
     * why this is not past the CARRIAGES view any more.
     */
    public static final int CONTENTS_FIRST_Z = 0;

    /**
     * Tighter inter-plot gap used inside a contents-group column (parent →
     * its sub-variants stacked along +Z). Yields {@code (GAP - 2)} air blocks
     * between bedrock cages.
     */
    public static final int SUB_VARIANT_GAP = 4;

    /** First Z slot of the track-side row (TRACKS and PORTALS views). The shared origin. */
    public static final int TRACKS_FIRST_Z = 0;

    /**
     * Shared plot floor for every editor — the Y every plot's origin sits at.
     *
     * <p>Set by the tallest thing a plot has to stand up, which is a portal room at
     * {@link games.brennan.dungeontrain.portal.PortalRoomLayout#MAX_HEIGHT} (90): a plot floor plus
     * that has to stay under the build ceiling (320 in every DT preset), and 230 leaves ten blocks
     * of margin. It was 250, which capped an authored room at 70 — a ceiling nothing about rooms
     * asked for, and one an author hit with no way to see why.</p>
     *
     * <p>Low enough for the sky, still far above anything gameplay does: trains run at y=78 by
     * default. {@link #isAtPlotHeight} is the "is this player up at the editor" test derived from
     * it, so no caller writes the number out again.</p>
     */
    public static final int PLOT_Y = 230;

    /**
     * Margin below {@link #PLOT_Y} that still counts as "up at the editor" — a player standing on
     * their plot floor is a few blocks below the origin, and a gate left ABOVE the floor silently
     * disables everything that keys off this.
     */
    private static final int PLOT_HEIGHT_MARGIN = 5;

    /**
     * Whether a player at this Y is up at the editor build area rather than down where the train
     * runs (y=78 by default).
     *
     * <p>Derived from {@link #PLOT_Y} and shared rather than repeated: it is the short-circuit that
     * keeps the per-player {@code plotContaining} cascade off the tick during ordinary play
     * ({@code VariantOverlayRenderer}), and it is what decides whether a player's seconds count as
     * editor time ({@code BuildingTimeEvents}). Two copies of the number would eventually disagree
     * about where the editor starts.</p>
     */
    public static boolean isAtPlotHeight(int y) {
        return y >= PLOT_Y - PLOT_HEIGHT_MARGIN;
    }

    private EditorLayout() {}
}
