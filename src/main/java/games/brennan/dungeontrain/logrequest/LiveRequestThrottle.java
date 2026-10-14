package games.brennan.dungeontrain.logrequest;

import games.brennan.dungeontrain.logrequest.DeliveryFailureReport.Reason;

/**
 * How often a livestream log request's failure is told to Brennan on Discord. The client relays
 * those requests ({@code LogRequestFromLivePacket}), so a modified client can send them in a loop;
 * every outcome still goes to the log, but the Discord post is held back once it has been made:
 * "too soon" once per rate-limit window, "already waiting on them" once per pending request.
 *
 * <p>Pure: a {@link State} in, a {@link Decision} with the next state out. Nothing is mutated.</p>
 */
public final class LiveRequestThrottle {

    /** Per-player memory: when the current window opened and what has been posted about it. */
    public record State(long windowStartMs, boolean tooSoonReported, int reportedPendingId) {
        /** No request yet: the next one opens a window. */
        public static final State NONE = new State(Long.MIN_VALUE, false, -1);
    }

    /** {@code tooSoon}: inside the window, the request is refused; {@code post}: tell Discord. */
    public record Decision(State next, boolean tooSoon, boolean post) {}

    private LiveRequestThrottle() {}

    /** A live request arrived at {@code now}; {@code gapMs} is the window length. */
    public static Decision arrive(State prev, long now, long gapMs) {
        if (prev.windowStartMs() != Long.MIN_VALUE && now - prev.windowStartMs() < gapMs) {
            return new Decision(new State(prev.windowStartMs(), true, prev.reportedPendingId()), true, !prev.tooSoonReported());
        }
        return new Decision(new State(now, false, prev.reportedPendingId()), false, false);
    }

    /**
     * The request opened a window but could not be shown. An already-pending request is posted
     * once per pending id; the other reasons once per window (this call happens once per window).
     */
    public static Decision blocked(State state, Reason reason, int pendingId) {
        if (reason != Reason.ALREADY_PENDING) return new Decision(state, false, true);
        boolean post = pendingId != state.reportedPendingId();
        return new Decision(new State(state.windowStartMs(), state.tooSoonReported(), pendingId), false, post);
    }
}
