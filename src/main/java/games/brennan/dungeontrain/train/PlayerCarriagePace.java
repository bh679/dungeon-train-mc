package games.brennan.dungeontrain.train;

import java.util.ArrayDeque;

/**
 * One player's average pace forward through the train, in <b>carriages per second</b>, over at most the
 * last {@code window} carriages they travelled this life — four Diff-Levels' worth in play. Time spent
 * idle or fighting since the oldest counted carriage stays in the average, the way it slows the player.
 * Walking backward counts as standing still: the train still carries them toward the landmark. A
 * {@link #PRIOR_SECONDS} prior at the seed pace smooths a fresh life until real data builds up. Fed every
 * tick from the appender with the player's carriage index; pure, so the maths unit-tests.
 */
public final class PlayerCarriagePace {

    /** Weight of the seed pace, in seconds of riding, blended into every average. */
    public static final double PRIOR_SECONDS = 20.0;

    /** A gap longer than this (off the train, logged out) is not counted as riding time. */
    public static final double MAX_GAP_SECONDS = 5.0;

    private final double seed;
    private final int window;
    /** One sample per forward step: {carriages travelled, seconds ridden} at that moment. */
    private final ArrayDeque<double[]> samples = new ArrayDeque<>();
    private double forwardCarriages;
    private double riddenSeconds;
    private int lastPIdx;
    private long lastTick = Long.MIN_VALUE;

    /**
     * A fresh tracker at {@code seedPace} carriages/s, averaging over the last {@code windowCarriages}
     * carriages travelled.
     */
    public PlayerCarriagePace(double seedPace, int windowCarriages) {
        this.seed = Math.max(0.0, seedPace);
        this.window = Math.max(1, windowCarriages);
    }

    /** Record that the player is in carriage {@code pIdx} at game tick {@code tick}. */
    public void observe(int pIdx, long tick) {
        if (lastTick != Long.MIN_VALUE && tick > lastTick) {
            double dt = (tick - lastTick) / 20.0;
            if (dt <= MAX_GAP_SECONDS) {
                riddenSeconds += dt;
                int forward = Math.max(0, pIdx - lastPIdx);
                if (forward > 0) {
                    forwardCarriages += forward;
                    samples.addLast(new double[] {forwardCarriages, riddenSeconds});
                }
            }
        }
        if (samples.isEmpty()) samples.addLast(new double[] {forwardCarriages, riddenSeconds});
        while (samples.size() > 1 && forwardCarriages - samples.peekFirst()[0] > window) samples.removeFirst();
        if (tick >= lastTick || lastTick == Long.MIN_VALUE) {
            lastPIdx = pIdx;
            lastTick = tick;
        }
    }

    /** Back to the seed pace — a new life. */
    public void reset() {
        samples.clear();
        forwardCarriages = 0.0;
        riddenSeconds = 0.0;
        lastTick = Long.MIN_VALUE;
    }

    /** Carriages per second over the window, blended with the seed prior. */
    public double pace() {
        double[] oldest = samples.peekFirst();
        double carriages = oldest == null ? 0.0 : forwardCarriages - oldest[0];
        double seconds = oldest == null ? 0.0 : riddenSeconds - oldest[1];
        return (carriages + seed * PRIOR_SECONDS) / (seconds + PRIOR_SECONDS);
    }
}
