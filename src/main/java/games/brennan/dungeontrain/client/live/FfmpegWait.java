package games.brennan.dungeontrain.client.live;

/**
 * The streamer's patience with Vista's ffmpeg download. A download that keeps reporting the same
 * percentage (or none at all: a hung connection) for {@link #STALL_MS} is given up on; one that is
 * slow but moving is not. Pure clock arithmetic, so the controller's wait is testable.
 */
final class FfmpegWait {

    /** How long the percentage may stand still before the stream gives up. */
    static final long STALL_MS = 90_000;

    private long startedMs;
    private long lastChangeMs;
    private int lastProgress = Integer.MIN_VALUE;

    /** Starts (or restarts) the wait at {@code nowMs}. */
    void reset(long nowMs) {
        startedMs = nowMs;
        lastChangeMs = nowMs;
        lastProgress = Integer.MIN_VALUE;
    }

    /**
     * Records the download's current percentage ({@code -1} when Vista cannot say). True when it has
     * not changed for {@link #STALL_MS}: the download is stalled.
     */
    boolean tick(int progress, long nowMs) {
        if (progress != lastProgress) {
            lastProgress = progress;
            lastChangeMs = nowMs;
            return false;
        }
        return nowMs - lastChangeMs >= STALL_MS;
    }

    long elapsedMs(long nowMs) {
        return nowMs - startedMs;
    }

    /** The last percentage seen, or -1 before any. */
    int lastProgress() {
        return lastProgress == Integer.MIN_VALUE ? -1 : lastProgress;
    }
}
