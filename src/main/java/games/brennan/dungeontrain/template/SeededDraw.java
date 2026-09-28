package games.brennan.dungeontrain.template;

/**
 * The one seeded hash the group-level lotteries share — a splitmix64 finaliser over
 * {@code (seed, ordinal)}, so a verdict for a given group is a pure function of the world seed and
 * the group's index and never of what happened to be rolled before it.
 *
 * <p>Identical to the hash {@code PortalCarriageSelection} draws with; lifted here so
 * {@code WholeGroupSelection} draws the same way without reaching into the portal package.
 * Callers salt the seed so two lotteries over the same ordinals are decorrelated.</p>
 */
public final class SeededDraw {

    /** Draws are compared against {@code 1/every} at this resolution. */
    public static final long PRECISION = 1_000_000L;

    private SeededDraw() {}

    public static long hash(long seed, long ordinal) {
        long h = seed ^ (ordinal * 0x9E3779B97F4A7C15L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return (h ^ (h >>> 31)) >>> 1;
    }

    /** True with probability {@code 1/every} for this {@code (seed, ordinal)}; never for {@code every <= 0}. */
    public static boolean hit(long seed, long ordinal, int every) {
        if (every <= 0) return false;
        if (every == 1) return true;
        return Math.floorMod(hash(seed, ordinal), PRECISION) < PRECISION / every;
    }
}
