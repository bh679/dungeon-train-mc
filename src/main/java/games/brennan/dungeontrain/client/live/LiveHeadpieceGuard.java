package games.brennan.dungeontrain.client.live;

/**
 * The streamer's own check that the Live Eye is on its head — the server's START is not trusted
 * for that. No frame is ever read without it ({@code LiveStreamController#capturePoint}); this
 * decides when a stream without it ends.
 *
 * <p>Ending waits {@link #GRACE_TICKS}: on login the server sends START before the client's
 * inventory may have arrived, and a real take-off is normally ended by the server's own
 * STOP_REMOVED first.</p>
 */
final class LiveHeadpieceGuard {

    /** Ticks the headpiece may be missing before the stream ends. */
    static final int GRACE_TICKS = 20;

    enum Verdict {
        /** Carry on. */
        NONE,
        /** Never worn since START: the stream must not start. */
        REFUSE,
        /** Was worn (or live) and has come off: end the stream. */
        REMOVED
    }

    private boolean seenWorn;
    private int offTicks;

    /** A new START. */
    void reset() {
        seenWorn = false;
        offTicks = 0;
    }

    /** One client tick of a running (or starting) stream. */
    Verdict tick(boolean worn, boolean live) {
        if (worn) {
            seenWorn = true;
            offTicks = 0;
            return Verdict.NONE;
        }
        if (++offTicks <= GRACE_TICKS) return Verdict.NONE;
        return seenWorn || live ? Verdict.REMOVED : Verdict.REFUSE;
    }
}
