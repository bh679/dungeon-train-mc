package games.brennan.dungeontrain.client.wind;

/**
 * The arithmetic behind {@link WindFeel}, kept free of Minecraft so it can be unit tested: how strongly the local
 * player feels the push, and which way it points on their screen.
 */
public final class WindFeelMath {

    /**
     * Wind on the player (blocks/tick², before grip) below which nothing wind-driven shows or plays: the calm
     * floor shared by the felt effects and the streak density ({@link #calmFade}).
     */
    public static final double CALM_FLOOR = 0.0002;
    /** Wind on the player at which the felt effects are full. */
    static final double FELT_FULL = 0.005;
    /** Push past which streak density is no longer faded ({@link #calmFade}). */
    static final double FADE_FULL = 0.002;
    /** Per-tick easing of {@link #felt} toward its target while rising / falling. */
    static final double FELT_RISE = 0.4;
    static final double FELT_FALL = 0.08;

    private WindFeelMath() {}

    /**
     * How strongly the player feels {@code windOnYou} (the wind hitting them while exposed, sneak and air factors in,
     * grip NOT taken off), 0..1, unsmoothed: 0 up to {@link #CALM_FLOOR}, then a square-root rise so faint early
     * wind already reads (Windy 0.04 ≈ 0.36, 0.08 ≈ 0.55), full at {@link #FELT_FULL}.
     */
    public static double feltTarget(double windOnYou) {
        if (!(windOnYou > CALM_FLOOR)) return 0.0;
        return Math.min(1.0, Math.sqrt((windOnYou - CALM_FLOOR) / (FELT_FULL - CALM_FLOOR)));
    }

    /**
     * Multiplier on how many wind visuals spawn for a push: 0 at or below {@link #CALM_FLOOR}, easing up to 1 at
     * {@link #FADE_FULL}, so a near-calm wind spawns nothing instead of a trickle.
     */
    public static double calmFade(double push) {
        if (!(push > CALM_FLOOR)) return 0.0;
        double x = Math.min(1.0, (push - CALM_FLOOR) / (FADE_FULL - CALM_FLOOR));
        return x * x * (3.0 - 2.0 * x);
    }

    /** One tick of easing from {@code current} toward {@code target}: quick to rise, slow to fall. */
    public static double easeFelt(double current, double target) {
        double rate = target > current ? FELT_RISE : FELT_FALL;
        double next = current + (target - current) * rate;
        return next < 1.0e-4 ? 0.0 : next;
    }

    /**
     * The wind's direction across the player's view: +1 = blowing toward the right of the screen, -1 = toward the
     * left, 0 = straight along the view. {@code yawDeg} is Minecraft's yaw (0 = facing +Z, 90 = facing -X).
     */
    public static double lateral(double dirX, double dirZ, double yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        // Facing (-sin, cos); the right of the screen is (-cos, -sin).
        return dirX * -Math.cos(yaw) + dirZ * -Math.sin(yaw);
    }

    /** The wind along the view: +1 = from behind (pushing forward), -1 = head-on. */
    public static double facing(double dirX, double dirZ, double yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        return dirX * -Math.sin(yaw) + dirZ * Math.cos(yaw);
    }
}
