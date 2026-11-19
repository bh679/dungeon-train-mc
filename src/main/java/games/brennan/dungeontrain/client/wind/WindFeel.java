package games.brennan.dungeontrain.client.wind;

import games.brennan.dungeontrain.wind.WindSample;

/**
 * How hard the wind is hitting the local player right now, for the "you are being pushed" effects. Fed once per client tick by {@link WindClientEvents} with the wind on the player while
 * exposed, before grip (so it is felt even when a standing player isn't sliding yet);
 * read by the sound ({@link WindSound}) and the edge streaks ({@link WindFeelOverlay}).
 */
public final class WindFeel {

    private static double felt = 0.0;
    private static double prevFelt = 0.0;
    private static double target = 0.0;
    private static double dirX = 1.0;
    private static double dirZ = 0.0;
    private static double strength = 0.0;
    private static double windOn = 0.0;

    private WindFeel() {}

    /**
     * One client tick: {@code windOnYou} is the wind hitting the player this tick (0 when sheltered or not
     * pushable), with the sneak and air factors but before grip, so it is felt even while standing firm.
     */
    static void update(double windOnYou, WindSample now) {
        prevFelt = felt;
        windOn = windOnYou;
        target = WindFeelMath.feltTarget(windOnYou);
        felt = WindFeelMath.easeFelt(felt, target);
        if (!now.isCalm()) {
            dirX = now.dirX();
            dirZ = now.dirZ();
        }
        strength = now.strength();
    }

    /** No wind here: let everything fade out. */
    static void calm() {
        update(0.0, WindSample.CALM);
    }

    static void reset() {
        felt = prevFelt = target = strength = windOn = 0.0;
    }

    /** Felt push 0..1, eased. */
    public static double felt() {
        return felt;
    }

    /** {@link #felt()} between ticks, for render code. */
    public static double felt(float partialTick) {
        return prevFelt + (felt - prevFelt) * partialTick;
    }

    /** This tick's unsmoothed felt push, 0..1. */
    public static double target() {
        return target;
    }

    /** Wind hitting the player this tick, before grip (blocks/tick²). */
    public static double windOnYou() {
        return windOn;
    }

    /** Wind strength at the player now (blocks/tick²). */
    public static double strength() {
        return strength;
    }

    public static double dirX() {
        return dirX;
    }

    public static double dirZ() {
        return dirZ;
    }
}
