package games.brennan.dungeontrain.client.wind;

/**
 * The arithmetic behind {@link WindFeel}, kept free of Minecraft so it can be unit tested: how strongly the local
 * player feels the push, and which way it points on their screen.
 */
public final class WindFeelMath {

    /** Push (blocks/tick², as applied by {@code WindPush}) at which the player feels the wind fully. */
    static final double FELT_FULL = 0.006;
    /** Per-tick easing of {@link #felt} toward its target while rising / falling. */
    static final double FELT_RISE = 0.4;
    static final double FELT_FALL = 0.08;

    private WindFeelMath() {}

    /** How strongly a push of {@code applied} is felt, 0..1 (unsmoothed). */
    public static double feltTarget(double applied) {
        if (!(applied > 0.0)) return 0.0;
        return Math.min(1.0, applied / FELT_FULL);
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
