package games.brennan.dungeontrain.perks.tier1;

/**
 * See Trade Values (Trader) maths, common side so the server-side stat registration never loads
 * the client tooltip. The ceiling starts at 1 emerald and each rank adds {@link #CEILING_STEP_GROWTH}
 * more than the last (+1.8, +2.6, +3.4 ...): rank 10 sees up to 46 emeralds. Uncapped.
 */
public final class SeeTradeValues {

    /** Extra emeralds each rank's step adds over the previous step. */
    public static final double CEILING_STEP_GROWTH = 0.8;

    private SeeTradeValues() {}

    /** Highest value (emeralds) visible at {@code rank}: {@code r + 0.4 r(r-1)}; 0 at rank 0. */
    public static double ceilingEmeralds(int rank) {
        int r = Math.max(0, rank);
        return r + CEILING_STEP_GROWTH * r * (r - 1) / 2.0;
    }

    /** Whether a value in sixteenths is visible at {@code rank}. */
    public static boolean visibleAt(int sixteenths, int rank) {
        return sixteenths > 0 && sixteenths <= ceilingEmeralds(rank) * 16.0;
    }
}
