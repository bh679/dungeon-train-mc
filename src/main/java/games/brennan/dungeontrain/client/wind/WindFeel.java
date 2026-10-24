package games.brennan.dungeontrain.client.wind;

import games.brennan.dungeontrain.wind.WindSample;

/**
 * How hard the wind is pushing the local player right now, for the "you are being pushed" effects
 * ({@link WindFeelMode}). Fed once per client tick by {@link WindClientEvents} with the push it actually applied;
 * read by the sound ({@link WindSound}), the screen overlay ({@link WindFeelOverlay}) and the camera
 * ({@link WindFeelCamera}).
 */
public final class WindFeel {

    /** How quickly a gust's shudder dies away, per tick. */
    private static final double SHUDDER_DECAY = 0.85;
    /** A gust shudders the view once it is this much of the wind and the push is felt this much. */
    private static final double SHUDDER_GUST = 0.5;
    private static final double SHUDDER_FELT = 0.2;

    private static double felt = 0.0;
    private static double prevFelt = 0.0;
    private static double target = 0.0;
    private static double shudder = 0.0;
    private static double dirX = 1.0;
    private static double dirZ = 0.0;
    private static double strength = 0.0;
    private static long ticks = 0L;

    private WindFeel() {}

    /** One client tick: {@code applied} is the push given to the player this tick (0 when sheltered). */
    static void update(double applied, WindSample now) {
        ticks++;
        prevFelt = felt;
        target = WindFeelMath.feltTarget(applied);
        felt = WindFeelMath.easeFelt(felt, target);
        boolean gusting = now.gust01() > SHUDDER_GUST && target > SHUDDER_FELT;
        shudder = Math.max(shudder * SHUDDER_DECAY, gusting ? now.gust01() : 0.0);
        if (shudder < 1.0e-3) shudder = 0.0;
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
        felt = prevFelt = target = shudder = strength = 0.0;
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

    public static double shudder() {
        return shudder;
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

    /** Client ticks seen, for animation phase. */
    public static long ticks() {
        return ticks;
    }
}
