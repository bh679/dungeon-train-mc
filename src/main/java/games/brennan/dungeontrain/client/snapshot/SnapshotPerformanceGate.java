package games.brennan.dungeontrain.client.snapshot;

/**
 * Pure decision logic for "is the game running well enough to spend a ride snapshot
 * right now?". A capture forces an extra camera override plus a GPU framebuffer
 * read-back on the render thread (see {@link RideSnapshotCapture}), so it is only
 * taken while the client (and, in single-player, the server) has headroom — otherwise
 * the shot is skipped so it doesn't stutter a game that is already struggling.
 *
 * <p>No Minecraft types: the caller reads the live FPS / tick-time and passes plain
 * numbers in, so this stays unit-testable (mirrors {@link SnapshotCooldowns}).</p>
 */
public final class SnapshotPerformanceGate {

    /** A perfectly-keeping-up server ticks at 20 TPS; tick time can only push it below this. */
    public static final double MAX_TPS = 20.0;

    private SnapshotPerformanceGate() {}

    /**
     * Convert a server's average per-tick time (nanoseconds) to ticks-per-second,
     * capped at {@link #MAX_TPS}. A non-positive sample (no data yet) is treated as
     * healthy ({@code 20}) rather than as a stall.
     */
    public static double tpsFromTickNanos(long avgTickNanos) {
        if (avgTickNanos <= 0L) return MAX_TPS;
        double mspt = avgTickNanos / 1_000_000.0;
        return Math.min(MAX_TPS, 1000.0 / mspt);
    }

    /**
     * Healthy enough to capture? FPS must meet {@code minFps} ({@code 0} disables the
     * FPS gate). When TPS is known (single-player integrated server) it must meet
     * {@code minTps} ({@code 0} disables the TPS gate); when it is unknown (multiplayer,
     * where the client can't read the remote server's tick rate) the TPS gate never
     * blocks.
     */
    public static boolean ok(int fps, int minFps, double tps, int minTps, boolean tpsKnown) {
        if (minFps > 0 && fps < minFps) return false;
        if (tpsKnown && minTps > 0 && tps < minTps) return false;
        return true;
    }
}
