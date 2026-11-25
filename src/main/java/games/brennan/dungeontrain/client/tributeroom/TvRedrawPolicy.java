package games.brennan.dungeontrain.client.tributeroom;

/**
 * When a TV's picture is drawn again. A TV is only drawn once Vista asked for its picture lately (it
 * was on screen, its face turned to the camera, within Vista's range); then as often as how it is
 * being looked at needs: every frame under the crosshair (a click reads the hover the last draw
 * worked out), ten times a second near, four times far.
 *
 * <p>Pure: times in milliseconds, distances squared in blocks.</p>
 */
public final class TvRedrawPolicy {

    /** How long after Vista last asked for a picture the TV still counts as seen. */
    static final long SEEN_GRACE_MS = 250L;
    /** Unseen this long, a TV lets its target go (re-made when it is seen again). */
    static final long RELEASE_AFTER_MS = 5_000L;
    /** Within this many blocks the TV redraws at the near rate. */
    static final double NEAR_BLOCKS = 16.0;
    static final long NEAR_INTERVAL_MS = 100L;
    static final long FAR_INTERVAL_MS = 250L;

    private TvRedrawPolicy() {}

    /**
     * Whether to draw the picture this frame.
     *
     * @param lastSeenMs   when Vista last asked for this picture, {@code Long.MIN_VALUE} for never
     * @param lastDrawnMs  when it was last drawn, {@code Long.MIN_VALUE} for never (or since its target was freed)
     * @param dirty        something changed the page (a click, a page turn, a new screen) since the last draw
     * @param aimed        the crosshair is on the picture
     * @param distanceSq   squared distance from the camera to the TV
     */
    public static boolean shouldDraw(long nowMs, long lastSeenMs, long lastDrawnMs, boolean dirty, boolean aimed,
                                     double distanceSq) {
        if (!seen(nowMs, lastSeenMs)) return false;
        if (dirty || aimed || lastDrawnMs == Long.MIN_VALUE) return true;
        long interval = distanceSq <= NEAR_BLOCKS * NEAR_BLOCKS ? NEAR_INTERVAL_MS : FAR_INTERVAL_MS;
        return nowMs - lastDrawnMs >= interval;
    }

    /** Whether Vista asked for the picture recently enough for the TV to count as on screen. */
    public static boolean seen(long nowMs, long lastSeenMs) {
        return lastSeenMs != Long.MIN_VALUE && nowMs - lastSeenMs <= SEEN_GRACE_MS;
    }

    /** Whether a TV has gone unseen long enough to free its target. */
    public static boolean shouldRelease(long nowMs, long lastSeenMs) {
        return lastSeenMs == Long.MIN_VALUE || nowMs - lastSeenMs > RELEASE_AFTER_MS;
    }
}
