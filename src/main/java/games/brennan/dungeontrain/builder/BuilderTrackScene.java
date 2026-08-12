package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;

import java.util.ArrayList;
import java.util.List;

/**
 * The stretch of line the Train Builder shows around an open track template.
 *
 * <p>A track piece only means anything in company. A pillar section is a slab until you can see the
 * column it belongs to, its neighbours at their real spacing, and the arch reaching between them; a
 * track tile is four blocks of stone until you can see the line it continues. So opening any track
 * template puts you in a real elevated stretch of line, with everything but the piece you are
 * editing drawn as ghosts.</p>
 *
 * <p><b>The rules are the generator's, not copies of them.</b> Spacing, thickness, the arch profile
 * and the stairs anchor grid all come from {@link TrackGenerator}'s own public helpers. The point of
 * this preview is fidelity, so a second implementation of the arithmetic would defeat it the first
 * time either side was tuned.</p>
 *
 * <p>What makes that possible is the platform being <b>flat</b>. The real rules are terrain-driven —
 * every pillar X is chosen by probing the ground beneath it — but over one continuous grass floor
 * every probe returns the same answer, so the whole layout collapses to pure arithmetic on the
 * column height. That is why this class needs no level and can be tested outright.</p>
 */
public final class BuilderTrackScene {

    /**
     * How tall the columns stand under the line.
     *
     * <p>Chosen rather than derived, on two thresholds: it clears
     * {@code BOTTOM(3) + TOP(4)} with middles left over, so all three pillar sections are visible in
     * one column at once, and it is over {@code TALL_ARCH_HEIGHT_THRESHOLD}, so the arch is the full
     * six-column taper rather than the stub a short pillar gets. A preview that showed neither would
     * be a worse lie than no preview.</p>
     */
    public static final int COLUMN_HEIGHT = 12;

    private BuilderTrackScene() {}

    /** The row the columns stand on — the first free block over the builder's grass. */
    public static int groundY() {
        return BuilderWorldLayout.Y_STAND;
    }

    /**
     * The row the bed sits on.
     *
     * <p>A column runs from {@code bedY - 1} down to the ground, so the bed is one above the cap.</p>
     */
    public static int bedY() {
        return groundY() + COLUMN_HEIGHT + 1;
    }

    /** The row the rails sit on. */
    public static int railY() {
        return bedY() + 1;
    }

    /** The line's geometry, in the same shape the generator works in. */
    public static TrackGeometry geometry(CarriageDims dims) {
        // TrackGeometry derives bed and rail from the train's Y, so hand it the train Y this bed
        // implies rather than reaching past it — that keeps one definition of the offset.
        return TrackGeometry.from(dims, bedY() + 2);
    }

    /** Gap between columns, from the generator's own rule for a column this tall. */
    public static int spacing() {
        return TrackGenerator.computeSpacing(COLUMN_HEIGHT);
    }

    /** How many blocks thick a column is on X, likewise. */
    public static int thickness() {
        return TrackGenerator.computeThickness(spacing());
    }

    /** The arch taper stepping out from each side of a column, likewise. */
    public static int[] archProfile() {
        return TrackGenerator.archProfile(COLUMN_HEIGHT);
    }

    /**
     * The X of every column across the platform.
     *
     * <p>{@code floorMod(x, spacing) == 0} is the generator's own test, applied to flat ground where
     * every X qualifies on depth. Over real terrain the same test runs against a per-X ground probe,
     * which is what makes the spacing irregular there and regular here.</p>
     */
    public static List<Integer> pillarCentresX() {
        List<Integer> centres = new ArrayList<>();
        int spacing = spacing();
        for (int x = BuilderWorldLayout.MIN_XZ; x <= BuilderWorldLayout.MAX_XZ; x++) {
            if (Math.floorMod(x, spacing) == 0) {
                centres.add(x);
            }
        }
        return centres;
    }

    /** Lowest X of the column centred on {@code centreX} — odd thickness symmetric, even biased +X. */
    public static int columnMinX(int centreX) {
        return centreX - ((thickness() - 1) / 2);
    }

    /** Highest X of that column. */
    public static int columnMaxX(int centreX) {
        return centreX + thickness() / 2;
    }

    /**
     * The column a staircase is attached to, and which side it leads out on.
     *
     * @param x     centre X of the column
     * @param minusZ true when the staircase sits on the {@code -Z} side of the line
     */
    public record Stairs(int x, boolean minusZ) {}

    /**
     * Every staircase across the platform.
     *
     * <p>One per {@link TrackGenerator#MIN_STAIRS_SPACING} blocks of X on a seed-phased grid, snapped
     * to the nearest column, with the side alternating per anchor slot — the generator's rule, run
     * over the whole platform instead of chunk by chunk. The seed is the world's, so a builder world
     * puts its staircases where that world would.</p>
     */
    public static List<Stairs> stairs(long worldSeed) {
        List<Stairs> out = new ArrayList<>();
        List<Integer> centres = pillarCentresX();
        if (centres.isEmpty()) {
            return out;
        }
        int anchor = TrackGenerator.stairsAnchorInRange(worldSeed,
                BuilderWorldLayout.MIN_XZ, BuilderWorldLayout.MAX_XZ);
        for (; anchor != Integer.MIN_VALUE && anchor <= BuilderWorldLayout.MAX_XZ;
                anchor += TrackGenerator.MIN_STAIRS_SPACING) {
            int nearest = nearestColumn(centres, anchor);
            boolean minusZ = TrackGenerator.stairsSideForAnchor(worldSeed, anchor);
            out.add(new Stairs(nearest, minusZ));
        }
        return out;
    }

    private static int nearestColumn(List<Integer> centres, int anchorX) {
        int best = centres.get(0);
        for (int centre : centres) {
            if (Math.abs(centre - anchorX) < Math.abs(best - anchorX)) {
                best = centre;
            }
        }
        return best;
    }

    /**
     * Lowest Z of a staircase on the given side.
     *
     * <p>Flush against the corridor either way: the generator puts the {@code -Z} one in the three
     * columns before {@code trackZMin} and the {@code +Z} one in the three after {@code trackZMax}.</p>
     */
    public static int stairsMinZ(boolean minusZ, CarriageDims dims) {
        TrackGeometry g = geometry(dims);
        int depth = PillarAdjunct.STAIRS.zSize();
        return minusZ ? g.trackZMin() - depth : g.trackZMax() + 1;
    }

    // ---- the down-stairs scene ----
    //
    // A stairs *entrance* is a different situation from everything else here. It belongs to the
    // down-stairs case: the line is underground in a tunnel, a shaft climbs from it to the surface,
    // and the entrance is the pavilion capping that shaft. So its scene is not the elevated line at
    // all — it is a ground-level line, a tunnel around it, and a staircase going up.

    /** The line's geometry for the down-stairs scene: on the ground, where the corridor already is. */
    public static TrackGeometry groundGeometry(CarriageDims dims) {
        return TrackGeometry.from(dims, BuilderWorldLayout.TRAIN_Y);
    }

    /** Floor of the shaft — {@code bedY + 2} in the generator, the deck the stairs start from. */
    public static int shaftFloorY(CarriageDims dims) {
        return groundGeometry(dims).bedY() + 2;
    }

    /**
     * The surface the entrance sits on.
     *
     * <p>Two full stair stamps above the shaft floor: enough that the climb reads as a climb rather
     * than a step, and a whole number of stamps so the preview shows the repeat the generator
     * actually makes rather than a clipped one.</p>
     */
    public static int shaftSurfaceY(CarriageDims dims) {
        return shaftFloorY(dims) + 2 * PillarAdjunct.STAIRS.ySize();
    }

    /**
     * Lowest Z of the shaft.
     *
     * <p>One block further out than an up-staircase, which is where {@code downStairsOriginZ} puts
     * it — the shaft has to clear the tunnel wall the line runs through.</p>
     */
    public static int shaftMinZ(CarriageDims dims) {
        return groundGeometry(dims).trackZMax() + 2;
    }

    /**
     * Highest row a staircase reaches — three above the column's cap.
     *
     * <p>{@code bedY + 2} in the generator, which is deck height rather than bed height: the stairs
     * have to arrive at somewhere you can step off, not at the sleepers.</p>
     */
    public static int stairsTopY() {
        return bedY() + 2;
    }
}
