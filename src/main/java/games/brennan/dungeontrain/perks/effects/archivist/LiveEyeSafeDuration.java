package games.brennan.dungeontrain.perks.effects.archivist;

/**
 * Live Eye Safe Duration: each rank adds {@link #EXTRA_MS_PER_RANK} to the time a streamer cannot be
 * forcibly taken off air. The relay keeps two windows, the protected minutes a takeover needs a vote
 * for ({@code takeoverMinMs}) and the minutes the holder may still vote in ({@code holderVoteMs});
 * half of the extra goes to each. The claim carries both halves and the relay adds them to its
 * defaults, so an older relay simply ignores them. Pure.
 */
public final class LiveEyeSafeDuration {

    /** Five minutes per rank. Balancing knob. */
    public static final long EXTRA_MS_PER_RANK = 5L * 60L * 1000L;
    /** Ranks past this add nothing: an hour on top is as safe as a stream gets. Balancing knob. */
    public static final int MAX_RANK_COUNTED = 12;

    private LiveEyeSafeDuration() {}

    /** The whole extra for {@code rank}; 0 for rank 0 or less. */
    public static long extraMs(int rank) {
        int counted = Math.min(MAX_RANK_COUNTED, Math.max(0, rank));
        return counted * EXTRA_MS_PER_RANK;
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
