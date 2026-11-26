package games.brennan.dungeontrain.perks.effects.lover;

/**
 * Save Ally numbers (Brennan, 2026-10-28). Points 1 to 7 are set by hand; from point 8 the range
 * gains a block every two points and the wait eases toward 8 minutes, {@code 8 + (prev - 8) x 0.94}
 * per point, never reaching it. No cap, and no two points the same.
 */
public final class SaveAllyMath {

    private static final int[] RANGE = {6, 7, 8, 8, 9, 9, 10};
    private static final double[] EVERY_MINUTES = {30, 25, 21, 19, 17.5, 16.5, 16};
    private static final double FLOOR_MINUTES = 8.0;
    private static final double EASE = 0.94;

    private SaveAllyMath() {}

    /** Blocks from the Lover a friend can be saved; 0 at rank 0. */
    public static int range(int rank) {
        if (rank <= 0) return 0;
        if (rank <= RANGE.length) return RANGE[rank - 1];
        return RANGE[RANGE.length - 1] + (rank - 8) / 2;
    }

    /** Minutes between saves; 0 at rank 0. */
    public static double everyMinutes(int rank) {
        if (rank <= 0) return 0.0;
        if (rank <= EVERY_MINUTES.length) return EVERY_MINUTES[rank - 1];
        double last = EVERY_MINUTES[EVERY_MINUTES.length - 1];
        return FLOOR_MINUTES + (last - FLOOR_MINUTES) * Math.pow(EASE, rank - EVERY_MINUTES.length);
    }

    public static long everyTicks(int rank) {
        return Math.round(everyMinutes(rank) * 60.0 * 20.0);
    }
}
