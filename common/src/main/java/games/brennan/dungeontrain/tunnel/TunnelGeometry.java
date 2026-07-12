package games.brennan.dungeontrain.tunnel;

import games.brennan.dungeontrain.track.TrackGeometry;

/**
 * Derived tunnel layout computed from a {@link TrackGeometry}. The tunnel
 * hugs the track corridor — floor is the existing stone-brick bed — and
 * extends 3 blocks to each side of the 5-wide train and 3 blocks above the
 * train's 4-block-tall body.
 *
 * <p>Layout (Y increases upward):
 * <pre>
 *   y = ceilingY     stone-brick ceiling (13 wide, covers wall tops)
 *   y = ceilingY-1   top air row — 3 blocks above train top
 *   ...
 *   y = railY        rails at z = trackZMin+1 / trackZMax-1; air elsewhere
 *   y = floorY       stone-brick floor (11 wide = bed + 3 extra per side)
 * </pre>
 * Z layout:
 * <pre>
 *   z = wallMinZ      stone-brick wall column   (= trackZMin - 4)
 *   z = airMinZ..     11-wide airspace          (= trackZMin - 3 .. trackZMax + 3)
 *   z = wallMaxZ      stone-brick wall column   (= trackZMax + 4)
 * </pre>
 */
public record TunnelGeometry(
    int floorY,
    int ceilingY,
    int trackZMin,
    int trackZMax,
    int airMinZ,
    int airMaxZ,
    int wallMinZ,
    int wallMaxZ,
    int centerZ,
    int railY,
    int railZMin,
    int railZMax
) {

    /** Y offset from {@link TrackGeometry#bedY()} up to the tunnel ceiling (inclusive). */
    private static final int CEILING_OFFSET_ABOVE_BED = 9;

    /** Inward Z offset from the edge of the track corridor to the tunnel airspace edge. */
    private static final int AIRSPACE_Z_PAD = 3;

    /** Inward Z offset from the edge of the track corridor to the tunnel wall column. */
    private static final int WALL_Z_PAD = 4;

    public static TunnelGeometry from(TrackGeometry g) {
        return new TunnelGeometry(
            g.bedY(),
            g.bedY() + CEILING_OFFSET_ABOVE_BED,
            g.trackZMin(),
            g.trackZMax(),
            g.trackZMin() - AIRSPACE_Z_PAD,
            g.trackZMax() + AIRSPACE_Z_PAD,
            g.trackZMin() - WALL_Z_PAD,
            g.trackZMax() + WALL_Z_PAD,
            g.trackCenterZ(),
            g.railY(),
            g.trackZMin() + 1,
            g.trackZMax() - 1
        );
    }
}
