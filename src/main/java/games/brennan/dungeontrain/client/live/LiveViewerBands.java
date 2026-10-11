package games.brennan.dungeontrain.client.live;

/**
 * When the streamer is told how many are watching: once on going live, then each time the count
 * settles in a different band — 0, 1–10, 11–100, 101–1000, … — up or down.
 *
 * <p>A new band is only announced after it has held for {@link #HOLD_MS}, so a count wobbling across
 * a boundary (10 ↔ 11) says nothing until it stays put. Pure and single-threaded: the controller
 * feeds it from the client tick.</p>
 */
final class LiveViewerBands {

    /** How long a new band must hold before it is announced. */
    static final long HOLD_MS = 10_000L;

    private int announcedBand = -1;
    private int pendingBand = -1;
    private long pendingSinceMs;

    /** 0 → 0, 1–10 → 1, 11–100 → 2, 101–1000 → 3, and so on. Negative counts read as 0. */
    static int band(int viewers) {
        if (viewers <= 0) return 0;
        int band = 1;
        long limit = 10;
        while (viewers > limit) {
            limit *= 10;
            band++;
        }
        return band;
    }

    /** The going-live line was said with {@code viewers}: that band is the starting point. */
    void start(int viewers) {
        announcedBand = band(viewers);
        pendingBand = -1;
    }

    /**
     * Feeds the current count; true when {@code viewers} should be announced now. Counts below 0
     * (not yet known) and calls before {@link #start} are ignored.
     */
    boolean update(int viewers, long nowMs) {
        if (announcedBand < 0 || viewers < 0) return false;
        int band = band(viewers);
        if (band == announcedBand) {
            pendingBand = -1;
            return false;
        }
        if (band != pendingBand) {
            pendingBand = band;
            pendingSinceMs = nowMs;
            return false;
        }
        if (nowMs - pendingSinceMs < HOLD_MS) return false;
        announcedBand = band;
        pendingBand = -1;
        return true;
    }
}
