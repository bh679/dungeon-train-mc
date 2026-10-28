package games.brennan.dungeontrain.worldgen;

import org.joml.Vector3f;

import java.util.SplittableRandom;

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
    /** A comet this far below the train's lowest carriage (blocks) has missed it for good and ends. */
    public static final double MISS_DROP_BLOCKS = 16.0;
    /** Ticks for a comet's block to make one full turn as it falls. */
    public static final int SPIN_TICKS_PER_TURN = 30;
    /** Beyond this many blocks from where a comet lands, a player feels nothing. */
    public static final double SHAKE_RADIUS_BLOCKS = 40.0;
    /** A comet's roar starts to build once it is this many blocks away; it is a faint rush before that. */
    public static final double ROAR_REACH_BLOCKS = 128.0;
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

    /** Whether a comet at height {@code y} has fallen {@value #MISS_DROP_BLOCKS} blocks below a train whose lowest
     *  carriage bottom is at {@code trainBottomY}, and so can end without being seen to land. */
    public static boolean fellPastTrain(double y, double trainBottomY) {
        return y < trainBottomY - MISS_DROP_BLOCKS;
    }

    /** The tilted axis comet {@code id} spins about: a random unit vector seeded by the id, so every player who
     *  sees the comet sees it turn the same way. */
    public static Vector3f spinAxis(int id) {
        SplittableRandom random = new SplittableRandom(id);
        double z = random.nextDouble(-1.0, 1.0);
        double around = random.nextDouble(0.0, Math.PI * 2.0);
        double ring = Math.sqrt(1.0 - z * z);
        return new Vector3f((float) (ring * Math.cos(around)), (float) (ring * Math.sin(around)), (float) z);
    }

    /** How far comet {@code id} has turned about {@link #spinAxis} after {@code ticks}, in radians: one turn every
     *  {@value #SPIN_TICKS_PER_TURN} ticks, clockwise or not by the id. */
    public static float spinAngle(int id, double ticks) {
        double turns = ticks / SPIN_TICKS_PER_TURN;
        double sign = (id & 1) == 0 ? 1.0 : -1.0;
        return (float) (sign * (turns % 1.0) * Math.PI * 2.0);
    }

    /** How hard a comet landing {@code distance} blocks away shakes the screen: 1 at the impact, easing to 0 at
     *  {@value #SHAKE_RADIUS_BLOCKS} blocks, so a hit on your carriage jolts and one down the train only trembles. */
    public static double shakeStrength(double distance) {
        double closeness = Math.max(0.0, Math.min(1.0, 1.0 - distance / SHAKE_RADIUS_BLOCKS));
        return closeness * closeness;
    }

    /** How built-up an incoming comet's roar is at {@code distance} blocks: 0 out past
     *  {@value #ROAR_REACH_BLOCKS}, swelling steadily to 1 at the comet, so it never cuts in or drops out. */
    public static double roarBuild(double distance) {
        double closeness = Math.max(0.0, Math.min(1.0, 1.0 - distance / ROAR_REACH_BLOCKS));
        return Math.pow(closeness, 1.5);
    }

    /** The roar's pitch at {@code distance}: low and distant far off, rising to normal as the comet arrives. */
    public static float roarPitch(double distance) {
        return (float) (0.5 + 0.5 * roarBuild(distance));
    }
}
