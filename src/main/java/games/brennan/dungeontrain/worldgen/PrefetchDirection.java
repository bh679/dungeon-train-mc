package games.brennan.dungeontrain.worldgen;

/**
 * Which way along X a player is heading, for the End-band prefetch strip ({@code WorldEndBandEvents}).
 * Free of Minecraft types so it is unit-testable.
 *
 * <p>The train runs +X, but behind spawn the reversed cycle ({@link WorldGenCycle#reverseSlide}) is walked
 * in −X, and a strip prefetched only ahead in +X left those bands as void squares until their samples
 * landed. So the strip follows the player's own world-space movement — which includes being carried by
 * the train — and, when the player has hardly moved, the way the cycle is walked where they stand.</p>
 */
public final class PrefetchDirection {

    /** World-X movement (blocks) between prefetches below which a player counts as standing still. */
    public static final double MIN_MOVE_BLOCKS = 1.0;

    private PrefetchDirection() {}

    /**
     * {@code +1} or {@code -1}: the sign of {@code dxSinceLast} if the player moved at least
     * {@link #MIN_MOVE_BLOCKS}, else {@code -1} behind the cycle's anchor and {@code +1} ahead of it.
     * {@code dxSinceLast} may be {@link Double#NaN} (no previous position), which counts as standing still.
     */
    public static int pick(double dxSinceLast, boolean behindAnchor) {
        if (dxSinceLast >= MIN_MOVE_BLOCKS) return 1;
        if (dxSinceLast <= -MIN_MOVE_BLOCKS) return -1;
        return behindAnchor ? -1 : 1;
    }
}
