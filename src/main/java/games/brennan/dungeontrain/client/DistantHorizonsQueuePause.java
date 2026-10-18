package games.brennan.dungeontrain.client;

/**
 * The short pause {@code DhChunkUpdateQueueSpinMixin} adds to Distant Horizons' chunk-update queue thread.
 *
 * <p>DH's {@code ChunkUpdateQueueManager.runQueueingLoop} only sleeps when both of its queues are empty.
 * A queued chunk waits out a 250 ms debounce, and while it waits the loop pops it, finds it too young
 * (or finds the LOD executor missing or full), puts it back and goes round again with no pause, about
 * three million times a second on the trickle of chunk updates a train ride produces. Pausing for
 * {@link #PAUSE_MILLIS} after any such re-queue caps that at about a thousand passes a second, which
 * still drains the queue far faster than chunks arrive.</p>
 *
 * <p>Plain Java with no DH types, so it can be unit tested. {@code -PdhQueuePause=false} turns it off
 * (system property {@value #ENABLED_PROPERTY}) to measure DH as shipped on the same build.</p>
 */
public final class DistantHorizonsQueuePause {

    /** How long the queue thread waits after putting back an item it could not dispatch. */
    public static final long PAUSE_MILLIS = 1L;

    static final String ENABLED_PROPERTY = "dungeontrain.dh_queue_pause";

    private static final boolean ENABLED = isEnabled(System.getProperty(ENABLED_PROPERTY));

    private DistantHorizonsQueuePause() {}

    /** Whether the seam value leaves the pause on. Anything but {@code "false"} does. */
    static boolean isEnabled(String propertyValue) {
        return !"false".equalsIgnoreCase(propertyValue == null ? null : propertyValue.trim());
    }

    /**
     * Sleep {@link #PAUSE_MILLIS} on the calling (DH queue) thread. An interrupt ends the pause early
     * and is passed back on, so DH's {@code while (!Thread.interrupted())} loop still stops on
     * {@code shutdownNow()}.
     */
    public static void pause() {
        pause(ENABLED);
    }

    static void pause(boolean enabled) {
        if (!enabled) return;
        try {
            Thread.sleep(PAUSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
