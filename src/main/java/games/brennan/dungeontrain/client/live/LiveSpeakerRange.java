package games.brennan.dungeontrain.client.live;

/**
 * How far a placed TV's live sound carries, by the TV's size, and how loud it is at a distance. Pure.
 *
 * <p>A Vista TV is a grid of connected TV blocks ({@code connectedTvsAmount}: TVs across by TVs tall, each
 * 1 to the {@code max_connected_size} common config, 8 by default); its size here is the longer side in
 * TVs. A lone TV is heard {@link #RANGE_SMALLEST} blocks away, the largest grid {@link #RANGE_LARGEST},
 * sizes between on a straight line. Loudness falls on a straight line from full at the screen to nothing
 * at the range, the same shape as Minecraft's own linear-clamped rolloff, so a TV fades like any other
 * block sound, just over its own distance.</p>
 */
final class LiveSpeakerRange {

    /** Hearing range of a single TV, blocks. */
    static final double RANGE_SMALLEST = 9;
    /** Hearing range of the largest TV grid, blocks. */
    static final double RANGE_LARGEST = 18;
    /** The smallest TV: one block. */
    static final int SIZE_SMALLEST = 1;
    /** Vista's default {@code max_connected_size}, TVs a side, when its config cannot be read. */
    static final int DEFAULT_SIZE_LARGEST = 8;

    private LiveSpeakerRange() {}

    /**
     * Blocks a TV {@code size} TVs along its longer side is heard from, when the largest grid is
     * {@code largest} TVs a side: {@link #RANGE_SMALLEST} at {@link #SIZE_SMALLEST} up to
     * {@link #RANGE_LARGEST} at {@code largest}, clamped outside. With {@code largest} at or below the
     * smallest size every TV gets the largest range.
     */
    static double range(int size, int largest) {
        if (largest <= SIZE_SMALLEST) return RANGE_LARGEST;
        double t = (size - SIZE_SMALLEST) / (double) (largest - SIZE_SMALLEST);
        return RANGE_SMALLEST + (RANGE_LARGEST - RANGE_SMALLEST) * Math.clamp(t, 0, 1);
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
