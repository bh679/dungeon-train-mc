package games.brennan.dungeontrain.worldgen;

/**
 * How comet showers run at one depth, as pure math. Every strike on the train comes inside a shower: the sky fills
 * for {@code leadTicks}, then {@code strikesMean} strikes (rolled whole per shower, {@link #rollStrikes}) come down
 * one at a time, {@code gapTicks} apart, from the shower's one direction, at {@code speedScale} times a near miss's
 * speed; the sky stays full {@code tailTicks} after the last. Showers start at random, on average one every
 * {@code meanPeriodTicks}, which is set so strikes per minute match {@link SpheresComets#chancePerTick} x
 * {@link SpheresComets#aimedShare}: deeper means bigger, more frequent showers, not more strikes than before.
 *
 * <table>
 *   <tr><th>Where</th><th>Strikes</th><th>Gap</th><th>Lead / tail</th><th>Speed</th></tr>
 *   <tr><td>Lap 0</td><td>1 to 2 by depth</td><td>8 s</td><td>10 s / 15 s</td><td>x1</td></tr>
 *   <tr><td>Lap 1</td><td>3</td><td>4 s</td><td>6 s / 8 s</td><td>x1.5</td></tr>
 *   <tr><td>Lap 2</td><td>6</td><td>3 s</td><td>6 s / 8 s</td><td>x2</td></tr>
 *   <tr><td>Lap 3 on</td><td>8</td><td>2.5 s</td><td>6 s / 8 s</td><td>x2.5</td></tr>
 * </table>
 */
public record CometShowerPlan(double strikesMean, int gapTicks, int leadTicks, int tailTicks,
                              double meanPeriodTicks, double speedScale) {

    private static final double TICKS_PER_MINUTE = 1200.0;
    /** Shortest quiet between one shower ending and the next starting, on average. */
    private static final double MIN_IDLE_TICKS = 20.0;

    /** The plan at lap {@code cycleIndex}; {@code depth} (0..1, {@link SpheresComets#firstLapDepth}) only matters
     *  in lap 0. */
    public static CometShowerPlan of(long cycleIndex, double depth) {
        double strikesPerMinute = TICKS_PER_MINUTE * SpheresComets.chancePerTick(cycleIndex, 1.0, 1.0)
                * SpheresComets.aimedShare(cycleIndex, depth);
        if (cycleIndex <= 0L) {
            double d = Math.max(0.0, Math.min(1.0, depth));
            return make(1.0 + d, 160, 200, 300, 1.0, strikesPerMinute);
        }
        if (cycleIndex == 1L) return make(3.0, 80, 120, 160, 1.5, strikesPerMinute);
        if (cycleIndex == 2L) return make(6.0, 60, 120, 160, 2.0, strikesPerMinute);
        return make(8.0, 50, 120, 160, 2.5, strikesPerMinute);
    }

    private static CometShowerPlan make(double strikes, int gap, int lead, int tail, double speed,
                                        double strikesPerMinute) {
        return new CometShowerPlan(strikes, gap, lead, tail, TICKS_PER_MINUTE * strikes / strikesPerMinute, speed);
    }

    /** How long a shower of {@code strikes} lasts, lead to tail, if each strike flies in on time. */
    public int durationTicks(int strikes) {
        return leadTicks + Math.max(0, strikes - 1) * gapTicks + tailTicks;
    }

    /** Chance per tick of a shower starting over a train that has none on: one per the quiet left once an average
     *  shower is taken out of {@code meanPeriodTicks}. */
    public double startChancePerTick() {
        double busy = leadTicks + Math.max(0.0, strikesMean - 1.0) * gapTicks + tailTicks;
        return 1.0 / Math.max(MIN_IDLE_TICKS, meanPeriodTicks - busy);
    }

    /** Whole strikes for one shower from {@code u} in [0, 1): the mean's floor, plus one with its fraction's odds. */
    public int rollStrikes(double u) {
        int whole = (int) Math.floor(strikesMean);
        return Math.max(1, whole + (u < strikesMean - whole ? 1 : 0));
    }

    /** Strikes per minute this plan works out to, on average. */
    public double strikesPerMinute() {
        return TICKS_PER_MINUTE * strikesMean / meanPeriodTicks;
    }

    /** Share of the time the sky is in a shower, on average. */
    public double showerShare() {
        double busy = leadTicks + Math.max(0.0, strikesMean - 1.0) * gapTicks + tailTicks;
        return busy / Math.max(meanPeriodTicks, busy + MIN_IDLE_TICKS);
    }
}
