package games.brennan.dungeontrain.compat.vista;

/**
 * What a right-click does to a placed TV: it cycles off → Stream 1 → Stream 2 → off, where the
 * Stream 2 step exists only while a second stream is actually live (the Two Streams reward), so a
 * TV never parks on a stream nobody is on. Pure, so the cycle is pinned by a test; the mixin
 * ({@code mixin/vista/TVBlockMixin}) carries it out.
 */
public final class TvChannelCycle {

    private TvChannelCycle() {}

    public enum Step {
        /** The TV was off: switch it on (it auto-tunes to Stream 1). */
        TURN_ON,
        /** On Stream 1 with a second stream live: swap the tuner to Stream 2. */
        TO_SECOND,
        /** Anything else: switch it off (a Stream 2 cassette goes back to Stream 1 for the next cycle). */
        TURN_OFF
    }

    /**
     * @param on         the TV is switched on
     * @param liveTuned  its tuner holds a Live Feed cassette (either stream)
     * @param onSecond   that cassette is the second stream's
     * @param secondLive a second stream is live right now
     */
    public static Step next(boolean on, boolean liveTuned, boolean onSecond, boolean secondLive) {
        if (!on) return Step.TURN_ON;
        if (liveTuned && !onSecond && secondLive) return Step.TO_SECOND;
        return Step.TURN_OFF;
    }
}
