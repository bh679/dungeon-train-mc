package games.brennan.dungeontrain.perks.effects.archivist.follow;

import java.util.List;

/**
 * Favourite Author's numbers. Pure.
 *
 * <p>r = active follow slots. With more follows than slots the newest follows are active (the list is
 * kept newest first). When a community book spawns, the chance it is an unread book by an active
 * followed author is {@code 15% + 2% × active followed authors}; the same share applies to shared
 * photos placed in frames.</p>
 */
public final class FavouriteAuthor {

    /** The share with one or more active follows before the per-author part. Balancing knob. */
    public static final double BASE_SHARE = 0.15;
    /** Added per active followed author. Balancing knob; never written on the card. */
    public static final double PER_AUTHOR_SHARE = 0.02;

    private FavouriteAuthor() {}

    /** How many of {@code follows} follows are active at {@code rank}. */
    public static int activeCount(int follows, int rank) {
        return Math.max(0, Math.min(follows, rank));
    }

    /** Whether one more author can be followed: every follow needs an active point of its own. */
    public static boolean canFollowMore(int follows, int rank) {
        return rank > 0 && follows < rank;
    }

    /** The share at {@code active} active follows; 0 with none (nothing to find). */
    public static double share(int active) {
        return active <= 0 ? 0 : BASE_SHARE + PER_AUTHOR_SHARE * active;
    }

    /** The share the card shows: {@link #share} but never below the base, so 0 follows reads 15%. */
    public static double cardShare(int active) {
        return BASE_SHARE + PER_AUTHOR_SHARE * Math.max(0, active);
    }

    /** The newest {@code rank} follows of {@code follows} (kept newest first). */
    public static List<AuthorFollows.Follow> active(List<AuthorFollows.Follow> follows, int rank) {
        return follows.subList(0, activeCount(follows.size(), rank));
    }
}
