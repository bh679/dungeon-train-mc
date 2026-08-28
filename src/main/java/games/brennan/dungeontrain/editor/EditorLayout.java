package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriageDims;

/**
 * Shared layout constants for every editor (carriage, contents, parts,
 * track-side). The single {@link #GAP} value is the empty-block separation
 * between any two adjacent template footprints — same on every axis,
 * across every editor — so the world above {@link #PLOT_Y} reads as a
 * uniform grid.
 *
 * <p>The three editor categories (CARRIAGES, CONTENTS, TRACKS) each own a
 * disjoint {@code Z} range in plan view so that {@link
 * CarriageContentsEditor#plotContaining}, {@link
 * TrackSidePlots#locate}, and {@link CarriagePartEditor#plotContaining}
 * never claim the same world position. This keeps the editor status HUD
 * unambiguous and means a stale stamp in one category can't leave residue
 * in another category's plot footprint. The boundary constants below are
 * the single source of truth — each editor reads its baseline from here
 * rather than hard-coding a number.</p>
 *
 * <p>Layout (all at {@link #PLOT_Y}, in {@code +Z} order):
 * <ul>
 *   <li>{@code Z=0..MAX_WIDTH-1} — carriage row (CARRIAGES view)</li>
 *   <li>{@code Z=PARTS_FIRST_Z..CARRIAGES_VIEW_MAX_Z} — parts grid
 *       (CARRIAGES view): FLOOR / WALLS / ROOF / DOORS rows</li>
 *   <li>{@code Z=CONTENTS_FIRST_Z..CONTENTS_VIEW_MAX_Z} — contents row
 *       (CONTENTS view)</li>
 *   <li>{@code Z=TRACKS_FIRST_Z..} — track / pillar / tunnel / stair
 *       rows (TRACKS view)</li>
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

    /**
     * Maximum Z occupied by the CARRIAGES view at max dims — parts grid
     * extends in {@code +Z} from {@link #PARTS_FIRST_Z} by FLOOR + WALLS +
     * ROOF + DOORS rows separated by {@link #GAP}. Drives the
     * {@link #CONTENTS_FIRST_Z} baseline so the next view never overlaps.
     *
     * <p>FLOOR / ROOF row {@code Z = MAX_WIDTH - 2}; WALLS row
     * {@code Z = 1}; DOORS row {@code Z = MAX_WIDTH}; three inter-row
     * GAPs.</p>
     */
    public static final int CARRIAGES_VIEW_MAX_Z = PARTS_FIRST_Z
        + (CarriageDims.MAX_WIDTH - 2)        // FLOOR row Z
        + GAP + 1                             // gap + WALLS row Z
        + GAP + (CarriageDims.MAX_WIDTH - 2)  // gap + ROOF row Z
        + GAP + CarriageDims.MAX_WIDTH;       // gap + DOORS row Z

    /** First Z slot of the contents row (CONTENTS view). */
    public static final int CONTENTS_FIRST_Z = CARRIAGES_VIEW_MAX_Z + GAP;

    /**
     * Tighter inter-plot gap used inside a contents-group column (parent →
     * its sub-variants stacked along +Z). Yields {@code (GAP - 2)} air blocks
     * between bedrock cages.
     */
    public static final int SUB_VARIANT_GAP = 4;

    /**
     * Maximum sub-variants reserved per contents group. The CONTENTS view's
     * +Z extent is sized so a group of this many members fits inside the
     * region without overlapping the TRACKS view that follows. Authors who
     * exceed this cap will see the deepest sub-variants' cages bleed into
     * the TRACKS region — soft cap, not enforced at command time.
     */
    public static final int MAX_SUB_VARIANTS_PER_PARENT = 8;

    /**
     * Maximum Z occupied by the CONTENTS view at max dims — the parent's own
     * row footprint plus {@link #MAX_SUB_VARIANTS_PER_PARENT} reserved
     * sub-variant rows separated by {@link #SUB_VARIANT_GAP}. Drives the
     * {@link #TRACKS_FIRST_Z} baseline so sub-variant columns don't collide
     * with track-side plots.
     */
    public static final int CONTENTS_VIEW_MAX_Z = CONTENTS_FIRST_Z
        + CarriageDims.MAX_WIDTH
        + MAX_SUB_VARIANTS_PER_PARENT * (CarriageDims.MAX_WIDTH + SUB_VARIANT_GAP);

    /** First Z slot of the track-side row (TRACKS view). */
    public static final int TRACKS_FIRST_Z = CONTENTS_VIEW_MAX_Z + GAP;

    /**
     * Shared plot floor for every editor — the Y every plot's origin sits at.
     *
     * <p>Set by the tallest thing a plot has to stand up, which is a portal room at
     * {@link games.brennan.dungeontrain.portal.PortalRoomLayout#MAX_HEIGHT} (80): a plot floor plus
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
