package games.brennan.dungeontrain.client;

/**
 * Which Distant Horizons render distance to apply for a cap, chosen so DH reloads as rarely as
 * possible. Every change of DH's render distance rebuilds its LOD tree — visibly: artefacts, and
 * LOD sections popping back in — so the distance moves only between a few coarse tiers and holds
 * still wherever that is safe. Pure (no DH types), so it is unit-tested.
 *
 * <ul>
 *   <li><b>Tiers.</b> DH's minimum doubled ({@code 32, 64, 128, …}) below the player's own setting,
 *       plus that setting itself. An approach from 256 to a void is at most three reloads.</li>
 *   <li><b>Lower at once, raise with room to spare.</b> Lowering is never delayed — the view must not
 *       reach past a boundary — but raising waits until the next tier fits with
 *       {@link #RAISE_HEADROOM} to spare, so hovering at a tier edge cannot flap.</li>
 *   <li><b>Hold while hidden.</b> Below DH's minimum, DH is not drawing at all
 *       ({@link DistantHorizonsRenderCap#belowFloor()}), so the distance is left alone.</li>
 * </ul>
 */
public final class DhCapPolicy {

    /** Raise to a tier only once the allowed distance exceeds it by this factor. */
    static final double RAISE_HEADROOM = 1.25;

    /** "No override: DH is on the player's own setting." */
    public static final int RELEASED = -1;

    private DhCapPolicy() {}

    /**
     * The override to apply next.
     *
     * @param current   the override applied now, or {@link #RELEASED}
     * @param capChunks the allowed distance in chunks, or {@link Long#MAX_VALUE} when nothing narrows it
     * @param user      the player's own DH render distance (the ceiling)
     * @param min       DH's minimum render distance
     * @return the override to apply, or {@link #RELEASED} for the player's own setting
     */
    public static int next(int current, long capChunks, int user, int min) {
        int floor = Math.max(1, min);
        if (user <= floor) return RELEASED;             // nothing below the player's setting to go to
        if (capChunks < floor) return current;          // DH is hidden here: no reload for nothing
        int now = current == RELEASED ? user : Math.min(current, user);

        int fits = tierAtMost(capChunks, user, floor);
        if (fits < now) return toOverride(fits, user); // must lower now
        long roomyChunks = capChunks == Long.MAX_VALUE ? capChunks : (long) Math.floor(capChunks / RAISE_HEADROOM);
        int roomy = tierAtMost(roomyChunks, user, floor);
        return toOverride(Math.max(roomy, now), user);  // raise only with headroom; else hold
    }

    /** The largest tier not above {@code chunks} (at least {@code floor}). */
    static int tierAtMost(long chunks, int user, int floor) {
        if (chunks >= user) return user;
        int t = floor;
        while ((long) t * 2L <= chunks && t * 2 < user) t *= 2;
        return t;
    }

    private static int toOverride(int tier, int user) {
        return tier >= user ? RELEASED : tier;
    }
}
