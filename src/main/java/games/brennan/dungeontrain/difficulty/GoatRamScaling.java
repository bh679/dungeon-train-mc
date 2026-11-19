package games.brennan.dungeontrain.difficulty;

import games.brennan.dungeontrain.worldgen.CycleLayout;

/**
 * How hard a train goat headbutts, from where along the train it spawned. Pure (no Minecraft
 * types) so the curve is unit-testable; {@code event.GoatRamAggressionEvents} applies it.
 *
 * <p>Goats stay vanilla until the first Biomes O' Plenty overworld stretch. From there:</p>
 * <ul>
 *   <li><b>Wait between rams</b> (average): {@code 2 + 163·e^(-3.59·f)} seconds, {@code f} being
 *       progress from the BoP stretch to the start of cycle 2. Once that reaches
 *       {@link #GENTLE_FROM_SECONDS} the curve eases off: {@code 2 + 8·e^(-d/L)}, {@code d} blocks past
 *       that point. Never below {@link #FLOOR_SECONDS}.</li>
 *   <li><b>Damage</b>: 1 heart rising to 4 across cycle 1 from BoP, then +3 hearts per cycle, no cap.</li>
 * </ul>
 */
public final class GoatRamScaling {

    /** The wait the curve approaches and never crosses: one ram every 2 seconds. */
    static final double FLOOR_SECONDS = 2.0;
    /** Vanilla's average wait (600..6000 ticks → 165 s) less the floor: phase 1 starts at vanilla. */
    static final double PHASE1_SPAN_SECONDS = 163.0;
    /** Phase 1 fall-off per unit of BoP → cycle 2 progress. */
    static final double PHASE1_RATE = 3.59;
    /** Where the curve switches to the gentle tail. */
    static final double GENTLE_FROM_SECONDS = 10.0;
    /** Gentle tail e-folding length in blocks (17,300 nine-block carriages). */
    static final double GENTLE_LENGTH_BLOCKS = 155_700.0;
    /** Hearts gained across cycle 1 from the BoP stretch, and per cycle after that. */
    static final double HEARTS_PER_CYCLE = 3.0;
    /** Each goat's cooldown is sampled in {@code [1 - SPREAD, 1 + SPREAD] × average}. */
    public static final double SPREAD = 0.25;
    /** Shortest cooldown ever handed out, in ticks (1.5 s). */
    public static final int MIN_COOLDOWN_TICKS = 30;

    /** Phase 1 progress at which the wait reaches {@link #GENTLE_FROM_SECONDS}. */
    private static final double GENTLE_FROM_F =
            -Math.log((GENTLE_FROM_SECONDS - FLOOR_SECONDS) / PHASE1_SPAN_SECONDS) / PHASE1_RATE;

    private GoatRamScaling() {}

    /**
     * The parts of the world-gen layout the curve reads: where the first BoP overworld stretch
     * starts, and the cycle anchor and base period (cycle {@code k} starts at
     * {@code startX + period·(2^k - 1)}, see {@link CycleLayout#runStart}).
     */
    public record Layout(long bopX, long startX, long period) {

        /** A layout the curve can use, or {@code null} when BoP doesn't sit inside cycle 1. */
        public static Layout of(long bopX, long startX, long period) {
            if (period <= 0L || bopX < startX || bopX >= startX + period) return null;
            return new Layout(bopX, startX, period);
        }

        long cycleStart(int k) {
            return startX + CycleLayout.runStart(k, period);
        }
    }

    /** True when a goat spawned at world-X {@code x} gets the scaled behaviour (at or past BoP). */
    public static boolean isScaled(long x, Layout layout) {
        return layout != null && x >= layout.bopX();
    }

    /** Average seconds between rams at {@code x}; vanilla's 165 s before BoP. */
    public static double averageWaitSeconds(long x, Layout layout) {
        if (!isScaled(x, layout)) return FLOOR_SECONDS + PHASE1_SPAN_SECONDS;
        double span = layout.cycleStart(1) - layout.bopX();
        double f = (x - layout.bopX()) / span;
        if (f < GENTLE_FROM_F) {
            return FLOOR_SECONDS + PHASE1_SPAN_SECONDS * Math.exp(-PHASE1_RATE * f);
        }
        double past = (f - GENTLE_FROM_F) * span;
        return FLOOR_SECONDS + (GENTLE_FROM_SECONDS - FLOOR_SECONDS) * Math.exp(-past / GENTLE_LENGTH_BLOCKS);
    }

    /** Average ticks between rams at {@code x}, rounded. */
    public static int averageWaitTicks(long x, Layout layout) {
        return (int) Math.round(averageWaitSeconds(x, layout) * 20.0);
    }

    /** Hearts one headbutt deals at {@code x} (an adult goat's vanilla hit is 1 heart). */
    public static double hearts(long x, Layout layout) {
        if (!isScaled(x, layout)) return 1.0;
        long c1 = layout.cycleStart(1);
        if (x < c1) {
            return 1.0 + HEARTS_PER_CYCLE * (x - layout.bopX()) / (double) (c1 - layout.bopX());
        }
        int k = CycleLayout.runIndex(x - layout.startX(), layout.period());
        long from = layout.cycleStart(k);
        long to = layout.cycleStart(k + 1);
        double frac = (x - from) / (double) (to - from);
        return 1.0 + HEARTS_PER_CYCLE * (k + frac);
    }

    /** Extra attack damage (health points) over the vanilla adult hit, for an attribute modifier. */
    public static double bonusDamage(long x, Layout layout) {
        return 2.0 * (hearts(x, layout) - 1.0);
    }

    /** Smallest and largest cooldown a goat with this average may be given, in ticks. */
    public static int minCooldownTicks(int averageTicks) {
        return Math.max(MIN_COOLDOWN_TICKS, (int) Math.round(averageTicks * (1.0 - SPREAD)));
    }

    public static int maxCooldownTicks(int averageTicks) {
        return Math.max(minCooldownTicks(averageTicks), (int) Math.round(averageTicks * (1.0 + SPREAD)));
    }
}
