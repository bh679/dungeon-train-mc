package games.brennan.dungeontrain.client.live;

/**
 * When a dead streamer's stream is offered to viewers ({@code /live/died}): not at the moment of
 * death, but once every viewer's TV has shown it.
 *
 * <p>Viewers are behind the streamer. The segment holding the death is still being written when it
 * happens, then it has to go up and be listed, and each viewer's decoder joined at the newest listed
 * segment and plays at real speed — so a viewer can be up to about a segment behind the newest one
 * on storage, more with ffmpeg's playlist reload. The offer therefore waits until the uploader has
 * published the segment {@link #slackSegments} past the one holding the death plus
 * {@value #BEAT_S} s (the fall and the death screen coming up, not just the killing blow). If uploads
 * stall it goes out after {@value #FALLBACK_MS} ms anyway, so a death is never left unoffered; the
 * relay's window only opens when the offer arrives.</p>
 *
 * <p>Render thread; the decisions are pure so they can be tested.</p>
 */
final class LiveDeathHandover {

    /** Seconds after the death viewers should have seen before the offer. */
    static final double BEAT_S = 2.0;
    /** Segments a viewer can trail the newest published one by. */
    static final int VIEWER_SLACK_SEGMENTS = 2;
    /**
     * ...and never less than this many seconds: short segments (2 s, Quicker Streams) leave the
     * viewers' fixed lags (the edge-cached playlist, ffmpeg's reload, hls.js's own segment of buffer)
     * no longer covered by two segments.
     */
    static final int VIEWER_SLACK_MIN_S = 6;
    /** The longest the offer waits on uploads. */
    static final long FALLBACK_MS = 60_000;

    private final long targetSegment;
    private final long diedAtMs;
    private boolean sent;

    LiveDeathHandover(double deathS, int segmentS, long diedAtMs) {
        this.targetSegment = targetSegment(deathS, segmentS);
        this.diedAtMs = diedAtMs;
    }

    /** The segment whose publication means every viewer has seen the death. */
    static long targetSegment(double deathS, int segmentS) {
        return (long) Math.floor((Math.max(0, deathS) + BEAT_S) / Math.max(1, segmentS)) + slackSegments(segmentS);
    }

    /** Viewer slack in segments: {@value #VIEWER_SLACK_SEGMENTS} (10 s → 2), at least {@value #VIEWER_SLACK_MIN_S} s (2 s → 3). */
    static int slackSegments(int segmentS) {
        int s = Math.max(1, segmentS);
        return Math.max(VIEWER_SLACK_SEGMENTS, (VIEWER_SLACK_MIN_S + s - 1) / s);
    }

    long targetSegment() {
        return targetSegment;
    }

    /** Whether the offer may go out now: the target segment is published, or the fallback ran out. */
    static boolean ready(long publishedThrough, long targetSegment, long diedAtMs, long nowMs) {
        return publishedThrough >= targetSegment || nowMs - diedAtMs >= FALLBACK_MS;
    }

    /** True exactly once, the first time the offer may go out. */
    boolean release(long publishedThrough, long nowMs) {
        if (sent || !ready(publishedThrough, targetSegment, diedAtMs, nowMs)) return false;
        sent = true;
        return true;
    }

    long waitedMs(long nowMs) {
        return nowMs - diedAtMs;
    }
}
