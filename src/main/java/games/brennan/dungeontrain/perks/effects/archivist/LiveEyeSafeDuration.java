package games.brennan.dungeontrain.perks.effects.archivist;

/**
 * Live Eye Safe Duration: each rank adds some time a streamer cannot be forcibly taken off air, with
 * the step shrinking as ranks climb: 5, 4, 3, 2, 1.5 minutes for ranks 1 to 5 (totals 5, 9, 12,
 * 14, 15.5), then {@code 1.5 * 5 / rank} minutes per rank after that. No cap: the total keeps
 * growing, ever more slowly (about 20 min at rank 10, 37 min at rank 100). The relay keeps two windows, the
 * protected minutes a takeover needs a vote for ({@code takeoverMinMs}) and the minutes the holder
 * may still vote in ({@code holderVoteMs}); half of the extra goes to each. The claim carries both
 * halves and the relay adds them to its defaults, so an older relay simply ignores them. Pure.
 */
public final class LiveEyeSafeDuration {

    private static final long MINUTE_MS = 60L * 1000L;
    /** Minutes added by ranks 1 to 5. Balancing knob. */
    static final double[] FIRST_STEPS_MIN = {5.0, 4.0, 3.0, 2.0, 1.5};
    /** Step after the table: {@code TAIL_STEP_MIN * FIRST_STEPS_MIN.length / rank}. Balancing knob. */
    static final double TAIL_STEP_MIN = 1.5;

    private LiveEyeSafeDuration() {}

    /** Minutes the {@code rank}-th rank adds on its own; 0 for rank 0 or less. */
    static double stepMinutes(int rank) {
        if (rank <= 0) return 0.0;
        if (rank <= FIRST_STEPS_MIN.length) return FIRST_STEPS_MIN[rank - 1];
        return TAIL_STEP_MIN * FIRST_STEPS_MIN.length / rank;
    }

    /** The whole extra for {@code rank}; 0 for rank 0 or less. */
    public static long extraMs(int rank) {
        double minutes = 0.0;
        for (int r = 1; r <= rank; r++) minutes += stepMinutes(r);
        return Math.round(minutes * MINUTE_MS);
    }

    /** The first window's share: half, rounded up. */
    public static long firstWindowExtraMs(int rank) {
        long extra = extraMs(rank);
        return extra - extra / 2;
    }

    /** The second window's share: the rest. */
    public static long secondWindowExtraMs(int rank) {
        return extraMs(rank) / 2;
    }
}
