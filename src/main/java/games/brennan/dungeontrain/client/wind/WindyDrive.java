package games.brennan.dungeontrain.client.wind;

/**
 * The values DT hands Windy through its Living Wind hook ({@code WindyLivingWindCompatMixin}), refreshed once
 * per client tick by {@link WindClientEvents} from the wind {@code visualLeadTicks} ahead — so Windy's streaks
 * show each gust before it shoves.
 *
 * <p>Units: Windy treats a Living Wind strength below 0.08 as calm, scales spawn density by
 * {@code strength / 0.5} (capped at 4×) and switches to its strong streak at 0.75. {@link #REFERENCE_PUSH}
 * maps DT's push onto that scale: very slight wind (0.002) sits just over Windy's floor, a late steady wind
 * (~0.014) just under the strong streak, and gusts cross it.</p>
 */
public final class WindyDrive {

    /** DT push (blocks/tick²) that reads as Living Wind strength 1. */
    static final double REFERENCE_PUSH = 0.02;
    private static final float MAX_STRENGTH = 2.0f;

    private static volatile boolean owns = false;
    private static volatile float strength = 0.0f;
    private static volatile float yaw = 0.0f;

    private WindyDrive() {}

    /** True while DT is driving Windy (a train world, in the Overworld). Calm still counts — that's the point. */
    public static boolean owns() {
        return owns;
    }

    public static float strength() {
        return strength;
    }

    /** Minecraft yaw degrees the wind blows toward (Windy moves particles by {@code -sin(yaw), cos(yaw)}). */
    public static float yawDegrees() {
        return yaw;
    }

    static void set(boolean ownsIn, double push, double headingRad) {
        strength = (float) Math.min(MAX_STRENGTH, Math.max(0.0, push / REFERENCE_PUSH));
        // heading h: (cos h, sin h); yaw y: (-sin y, cos y)  =>  y = h - 90°
        yaw = (float) (Math.toDegrees(headingRad) - 90.0);
        owns = ownsIn;
    }

    static void release() {
        owns = false;
        strength = 0.0f;
    }
}
