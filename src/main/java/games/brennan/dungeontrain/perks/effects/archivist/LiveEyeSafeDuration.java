package games.brennan.dungeontrain.perks.effects.archivist;

/**
 * Live Eye Safe Duration: each rank adds some time a streamer cannot be forcibly taken off air, and
 * no rank ever adds more than the one before it:
 * <ul>
 *   <li>ranks 1 to 5: 5, 4, 3, 2, 1.5 minutes (totals 5, 9, 12, 14, 15.5);</li>
 *   <li>ranks 6 to 20: 1.5 minutes less {@link #MID_DROP_MIN} per rank, reaching 35 minutes at rank 20;</li>
 *   <li>past rank 20: the step shrinks by {@link #TAIL_RATIO} each rank, down to about 5 s at
 *       45 minutes (rank {@link #SLOW_FROM_RANK});</li>
 *   <li>after that it keeps shrinking, slowly: {@code 5 s * sqrt(46 / rank)} (3.4 s at rank 100,
 *       1 s at rank 1000). No cap: the total never stops growing.</li>
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
    /** Where the fast fall hands over to the slow one. Balancing knob. */
    static final int SLOW_FROM_RANK = 46;
    /** What a rank adds at {@link #SLOW_FROM_RANK}: 5 seconds. Balancing knob. */
    static final double SLOW_STEP_MIN = 5.0 / 60.0;

    private LiveEyeSafeDuration() {}

    /** Minutes the {@code rank}-th rank adds on its own; 0 for rank 0 or less. */
    static double stepMinutes(int rank) {
        if (rank <= 0) return 0.0;
        int table = FIRST_STEPS_MIN.length;
        if (rank <= table) return FIRST_STEPS_MIN[rank - 1];
        double last = FIRST_STEPS_MIN[table - 1];
        if (rank <= MID_LAST_RANK) return last - MID_DROP_MIN * (rank - table);
        double midEnd = last - MID_DROP_MIN * (MID_LAST_RANK - table);
        double fast = midEnd * Math.pow(TAIL_RATIO, rank - MID_LAST_RANK);
        double slow = SLOW_STEP_MIN * Math.sqrt((double) SLOW_FROM_RANK / rank);
        return Math.max(fast, slow);
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
