package games.brennan.dungeontrain.worldgen;

/**
 * When comets fall in the spheres band, as pure math so it can be tested off-thread. The server side that
 * spawns and flies them is {@code event.SpheresCometEvents}.
 *
 * <ul>
 *   <li><b>First time through</b> ({@code cycleIndex == 0}) — only once the sky has gone void: where the spheres
 *       band's End sky ({@link SpheresSky}) is at least {@value #FIRST_LAP_MIN_END_SKY}. About one every
 *       {@value #FIRST_LAP_INTERVAL_SECONDS} s.</li>
 *   <li><b>Second time through</b> — across the whole band from the moment the terrain starts dissolving,
 *       about one every {@value #SECOND_LAP_INTERVAL_SECONDS} s.</li>
 *   <li><b>Every lap after</b> — the interval halves per lap, never below {@value #MIN_INTERVAL_SECONDS} s.</li>
 * </ul>
 *
 * <p>Arrivals are memoryless: each tick a train rolls {@link #chancePerTick}, so the gaps are random around the
 * mean rather than a metronome.</p>
 */
public final class SpheresComets {

    static final double FIRST_LAP_MIN_END_SKY = 0.5;
    static final double FIRST_LAP_INTERVAL_SECONDS = 20.0;
    static final double SECOND_LAP_INTERVAL_SECONDS = 5.0;
    static final double MIN_INTERVAL_SECONDS = 2.0;
    /** Share of comets aimed at the train; the rest cross the sky on near-miss lines. */
    public static final double AIMED_SHARE = 1.0 / 3.0;
    private static final int TICKS_PER_SECOND = 20;

    private SpheresComets() {}

    /**
     * Mean seconds between comets in lap {@code cycleIndex} (0-based): {@value #FIRST_LAP_INTERVAL_SECONDS} on the
     * first, {@value #SECOND_LAP_INTERVAL_SECONDS} on the second, halving per lap after down to
     * {@value #MIN_INTERVAL_SECONDS}.
     */
    public static double intervalSeconds(long cycleIndex) {
        if (cycleIndex <= 0L) return FIRST_LAP_INTERVAL_SECONDS;
        long extraLaps = Math.min(64L, cycleIndex - 1L);
        return Math.max(MIN_INTERVAL_SECONDS, SECOND_LAP_INTERVAL_SECONDS * Math.pow(0.5, extraLaps));
    }

    /**
     * Whether comets fall here: always over the spheres band's void ({@code spheresVoid > 0}), and in lap 0 only
     * once its sky has gone void too ({@code endSky >=} {@value #FIRST_LAP_MIN_END_SKY}).
     */
    public static boolean active(long cycleIndex, double endSky, double spheresVoid) {
        if (!(spheresVoid > 0.0)) return false;
        return cycleIndex > 0L || endSky >= FIRST_LAP_MIN_END_SKY;
    }

    /** Chance per tick of a comet in lap {@code cycleIndex}, or 0 where {@link #active} is false. */
    public static double chancePerTick(long cycleIndex, double endSky, double spheresVoid) {
        if (!active(cycleIndex, endSky, spheresVoid)) return 0.0;
        return 1.0 / (intervalSeconds(cycleIndex) * TICKS_PER_SECOND);
    }
}
