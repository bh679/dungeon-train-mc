package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriageDims;

/**
 * Shared layout constants for every editor (carriage, contents, parts,
 * track-side). The single {@link #GAP} value is the empty-block separation
 * between any two adjacent template footprints — same on every axis,
 * across every editor — so the world above {@code y=250} reads as a
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
 * <p>Layout (all at {@code Y=250}, in {@code +Z} order):
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
     * First Z slot of the parts grid inside the CARRIAGES view. Sits
     * {@code GAP + 1} blocks past the carriage row's max-width footprint
     * so the cage of a max-width carriage and the cage of the first parts
     * row are separated by visible air.
     */
    public static final int PARTS_FIRST_Z = CarriageDims.MAX_WIDTH + GAP + 3;

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
     * Maximum Z occupied by the CONTENTS view at max dims — contents row
     * footprint depth equals {@code dims.width()}, capped at
     * {@link CarriageDims#MAX_WIDTH}. Drives the {@link #TRACKS_FIRST_Z}
     * baseline.
     */
    public static final int CONTENTS_VIEW_MAX_Z = CONTENTS_FIRST_Z + CarriageDims.MAX_WIDTH;

    /** First Z slot of the track-side row (TRACKS view). */
    public static final int TRACKS_FIRST_Z = CONTENTS_VIEW_MAX_Z + GAP;

    private EditorLayout() {}
}
