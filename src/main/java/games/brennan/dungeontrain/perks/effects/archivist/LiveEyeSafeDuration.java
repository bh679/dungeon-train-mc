package games.brennan.dungeontrain.perks.effects.archivist;

/**
 * Live Eye Safe Duration: each rank adds some time a streamer cannot be forcibly taken off air, and
 * no rank ever adds more than the one before it:
 * <ul>
 *   <li>ranks 1 to 5: 5, 4, 3, 2, 1.5 minutes (totals 5, 9, 12, 14, 15.5);</li>
 *   <li>ranks 6 to 20: 1.5 minutes less {@link #MID_DROP_MIN} per rank, reaching 35 minutes at rank 20;</li>
 *   <li>past rank 20: the step shrinks by {@link #TAIL_RATIO} each rank until it is
 *       {@link #FLOOR_STEP_MIN} (5 s, at about 45 minutes, rank 46), then stays there. No cap.</li>
 * </ul>
 * The relay keeps two windows, the protected minutes a takeover needs a vote for
 * ({@code takeoverMinMs}) and the minutes the holder may still vote in ({@code holderVoteMs}); half
 * of the extra goes to each. The claim carries both halves and the relay adds them to its defaults,
 * so an older relay simply ignores them. Pure.
 */
public final class LiveEyeSafeDuration {

    private static final long MINUTE_MS = 60L * 1000L;
    /** Minutes added by ranks 1 to 5. Balancing knob. */
    static final double[] FIRST_STEPS_MIN = {5.0, 4.0, 3.0, 2.0, 1.5};
    /** Last rank of the gently falling middle stretch. Balancing knob. */
    static final int MID_LAST_RANK = 20;
    /** How much less each middle rank adds than the one before. Balancing knob. */
    static final double MID_DROP_MIN = 0.025;
    /** Each rank past the middle adds this fraction of the one before. Balancing knob. */
    static final double TAIL_RATIO = 0.9057;
    /** The least a rank ever adds: 5 seconds. Balancing knob. */
    static final double FLOOR_STEP_MIN = 5.0 / 60.0;

    private LiveEyeSafeDuration() {}

    /** Minutes the {@code rank}-th rank adds on its own; 0 for rank 0 or less. */
    static double stepMinutes(int rank) {
        if (rank <= 0) return 0.0;
        int table = FIRST_STEPS_MIN.length;
        if (rank <= table) return FIRST_STEPS_MIN[rank - 1];
        double last = FIRST_STEPS_MIN[table - 1];
        if (rank <= MID_LAST_RANK) return last - MID_DROP_MIN * (rank - table);
        double midEnd = last - MID_DROP_MIN * (MID_LAST_RANK - table);
        return Math.max(FLOOR_STEP_MIN, midEnd * Math.pow(TAIL_RATIO, rank - MID_LAST_RANK));
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
