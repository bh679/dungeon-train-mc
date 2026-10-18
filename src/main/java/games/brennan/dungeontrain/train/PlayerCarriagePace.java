package games.brennan.dungeontrain.train;

/**
 * One player's average pace forward through the train, in <b>carriages per second</b> — an exponential
 * moving average over about a minute of play, pauses included (a fight in a carriage slows the average
 * the way it slows the player). Walking backward counts as standing still: the train still carries them
 * toward the landmark. Fed every tick from the appender with the player's carriage index; pure, so the
 * maths unit-tests.
 */
public final class PlayerCarriagePace {

    /** EMA time constant, seconds. */
    public static final double TIME_CONSTANT_SECONDS = 60.0;

    /** A gap longer than this (off the train, logged out) restarts the baseline instead of reading as idle. */
    public static final double MAX_GAP_SECONDS = 5.0;

    private double pace;
    private int lastPIdx;
    private long lastTick = Long.MIN_VALUE;

    /** A fresh tracker starting at {@code seedPace} carriages/s until it has measured the player. */
    public PlayerCarriagePace(double seedPace) {
        this.pace = Math.max(0.0, seedPace);
    }

    /** Record that the player is in carriage {@code pIdx} at game tick {@code tick}. */
    public void observe(int pIdx, long tick) {
        if (lastTick != Long.MIN_VALUE && tick > lastTick) {
            double dt = (tick - lastTick) / 20.0;
            if (dt <= MAX_GAP_SECONDS) {
                double inst = Math.max(0, pIdx - lastPIdx) / dt;
                double alpha = 1.0 - Math.exp(-dt / TIME_CONSTANT_SECONDS);
                pace += alpha * (inst - pace);
            }
        }
        if (tick >= lastTick || lastTick == Long.MIN_VALUE) {
            lastPIdx = pIdx;
            lastTick = tick;
        }
    }

    /** Carriages per second. */
    public double pace() {
        return pace;
    }
}
