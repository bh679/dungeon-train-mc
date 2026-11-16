package games.brennan.dungeontrain.client.live;

/**
 * How far a placed TV's live sound carries, by the TV's size, and how loud it is at a distance. Pure.
 *
 * <p>A Vista TV is a grid of connected TV blocks ({@code connectedTvsAmount}: TVs across by TVs tall);
 * its size here is the longer side in TVs. A lone TV is heard {@link #RANGE_SMALLEST} blocks away and
 * every extra TV along that side adds {@link #RANGE_PER_TV}: 4.5 blocks at 1x1, 9 at 4x4, and on up
 * for bigger screens with no ceiling. Loudness falls on a straight line from full at the screen to
 * nothing at the range, the same shape as Minecraft's own linear-clamped rolloff, so a TV fades like
 * any other block sound, just over its own distance.</p>
 */
final class LiveSpeakerRange {

    /** Hearing range of a single TV, blocks. */
    static final double RANGE_SMALLEST = 4.5;
    /** Blocks of range each TV beyond the first adds along the longer side: 4x4 reaches 9. */
    static final double RANGE_PER_TV = 1.5;
    /** The smallest TV: one block. */
    static final int SIZE_SMALLEST = 1;

    private LiveSpeakerRange() {}

    /**
     * Blocks a TV {@code size} TVs along its longer side is heard from: {@link #RANGE_SMALLEST} at
     * {@link #SIZE_SMALLEST}, plus {@link #RANGE_PER_TV} per extra TV, unbounded above. Sizes below the
     * smallest count as the smallest.
     */
    static double range(int size) {
        return RANGE_SMALLEST + RANGE_PER_TV * (Math.max(SIZE_SMALLEST, size) - SIZE_SMALLEST);
    }

    /** Share of full volume at {@code distance} blocks from a TV heard over {@code range}: 1 at the screen, 0 at the range and beyond. */
    static float gain(double distance, double range) {
        if (range <= 0 || distance >= range) return 0;
        if (distance <= 0) return 1;
        return (float) (1 - distance / range);
    }

    /** TVs along a side of the grid, from Vista's screen pixel size: each TV is 16 pixels, less a 4 pixel bezel. */
    static int tvsAlong(int screenPixels) {
        return Math.max(SIZE_SMALLEST, (screenPixels + 4) / 16);
    }
}
