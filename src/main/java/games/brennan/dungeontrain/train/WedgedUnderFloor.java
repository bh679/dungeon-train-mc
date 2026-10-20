package games.brennan.dungeontrain.train;

/**
 * Finds a mob stuck in a carriage floor (standing on the track bed under the carriage, its head
 * through the deck) and says where to put it instead.
 *
 * <p>Such a mob was never aboard: it got there because a spawner reading only the main level saw
 * the carriage's volume as air (see {@link CarriageSpace}). Until 0.1397.0 kill-ahead discarded it
 * on sight; once that stopped — so mobs that walk off the train survive — it rode along with its eyes
 * and head poking through the floor, an Enderman staring up from under the deck. This is the
 * safety net for any that still get in: they are moved out to the ground beside the carriage.</p>
 *
 * <p>Pure functions over primitives so they can be unit-tested without a game bootstrap. The
 * carriage box is its Sable world box: {@code minY} is the bottom of the floor block, so the deck
 * surface is {@code minY + 1}.</p>
 */
public final class WedgedUnderFloor {

    /**
     * How far below the deck surface a mob's feet must be before it counts as under the floor rather
     * than standing on it (Sable's carry is not exact to the millimetre).
     */
    static final double BELOW_DECK_BLOCKS = 0.5;

    /** Gap left between the carriage's side and the moved mob's box. */
    static final double SIDE_CLEARANCE = 0.05;

    private WedgedUnderFloor() {}

    /**
     * True when the mob's centre is inside the carriage footprint, its feet are below the deck,
     * and its eyes are in the floor or above it.
     *
     * <p>Eyes, not the top of the box: a spider on the ground beside the track grazes the bottom of
     * the floor with its back (0.09 of a block) as it walks at a rider, but nothing of it shows on
     * the deck. Counting that moved it out a hair's width, it walked straight back, and it was moved
     * again about once a second for minutes (0.1407.0 log, 279 lines).</p>
     */
    public static boolean isWedged(double x, double feetY, double z, double eyeY,
                                   double cMinX, double cMinY, double cMinZ,
                                   double cMaxX, double cMaxZ) {
        boolean inFootprint = x > cMinX && x < cMaxX && z > cMinZ && z < cMaxZ;
        double deckY = cMinY + 1.0;
        return inFootprint && feetY < deckY - BELOW_DECK_BLOCKS && eyeY > cMinY;
    }

    /**
     * Where to move a wedged mob, best first: just outside each long side of the carriage, nearer
     * side first. "Sides" are across the direction of travel ({@code velX}, {@code velZ}) so the
     * mob never lands in the runway ahead, where kill-ahead would take it. An idle carriage uses
     * whichever axis has the nearer edge.
     *
     * @param halfWidth half the mob's box width
     * @return two {x, z} pairs: {@code {x0, z0, x1, z1}}
     */
    public static double[] sideTargets(double x, double z, double halfWidth,
                                       double cMinX, double cMinZ, double cMaxX, double cMaxZ,
                                       double velX, double velZ) {
        boolean acrossZ;
        if (velX == 0 && velZ == 0) {
            double nearestX = Math.min(x - cMinX, cMaxX - x);
            double nearestZ = Math.min(z - cMinZ, cMaxZ - z);
            acrossZ = nearestZ <= nearestX;
        } else {
            acrossZ = Math.abs(velX) >= Math.abs(velZ);
        }

        double out = halfWidth + SIDE_CLEARANCE;
        if (acrossZ) {
            double low = cMinZ - out;
            double high = cMaxZ + out;
            boolean lowFirst = z - cMinZ <= cMaxZ - z;
            return lowFirst ? new double[] {x, low, x, high} : new double[] {x, high, x, low};
        }
        double low = cMinX - out;
        double high = cMaxX + out;
        boolean lowFirst = x - cMinX <= cMaxX - x;
        return lowFirst ? new double[] {low, z, high, z} : new double[] {high, z, low, z};
    }
}
