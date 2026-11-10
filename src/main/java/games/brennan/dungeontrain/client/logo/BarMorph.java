package games.brennan.dungeontrain.client.logo;

import static games.brennan.dungeontrain.client.logo.LogoRingGeometry.GAP;
import static games.brennan.dungeontrain.client.logo.LogoRingGeometry.R;

/**
 * The loading bar and its finish, as geometry: eight carriages in a line, filling with the
 * progress; then, once loaded, the line curls up into a circle (the carriages and their gaps
 * close exactly round a circle of radius 2R), and the circle draws in to the logo, each pair of
 * carriages sliding together into one of its four segments while the twist eases from a full turn
 * to the logo's half turn. The logo then keeps turning.
 *
 * <p>Every stage is the same tube with the same twist at its seams, so nothing jumps: the bend
 * keeps each carriage's length and twist, and the circle's twist and angles start where the bend
 * left them. Plain maths; {@link LogoRingRenderer} supplies the clock and draws it.</p>
 */
final class BarMorph {

    static final int CARRIAGES = 8;
    static final double BEND_SECONDS = 1.4;
    static final double MERGE_SECONDS = 1.4;

    /** World width the view shows: the straight bar, the circle of eight, the finished logo. */
    private static final double MARGIN = 0.12;
    private static final double VIEW_CIRCLE = 15.0;
    private static final double VIEW_LOGO = 10.0;

    static final double CAR = LogoRingGeometry.segmentLength();
    static final double GAP_LEN = LogoRingGeometry.gapLength();
    static final double TOTAL = CARRIAGES * CAR + (CARRIAGES - 1) * GAP_LEN;
    static final double MID = TOTAL / 2;
    /** The circle the eight carriages close round: radius 2R exactly. */
    static final double CIRCLE_R = 2 * R;
    private static final double SEG_ANGLE = CAR / R;

    private BarMorph() {}

    /** 0 → 1 over the bend, eased. */
    static double bend(double t) { return ease(t / BEND_SECONDS); }

    /** 0 → 1 over the draw-in to the logo, eased. */
    static double merge(double t) { return ease((t - BEND_SECONDS) / MERGE_SECONDS); }

    static boolean done(double t) { return t >= BEND_SECONDS + MERGE_SECONDS; }

    /** The view's world width at morph time t (negative t = the plain bar). */
    static double viewWidth(double t) {
        double bar = TOTAL + 2 * MARGIN;
        if (t <= BEND_SECONDS) return lerp(bar, VIEW_CIRCLE, bend(t));
        return lerp(VIEW_CIRCLE, VIEW_LOGO, merge(t));
    }

    /** Where the camera looks: the bar's middle, rising to the circle's centre as it bends. */
    static double[] lookAt(double t) { return new double[] { MID, CIRCLE_R * bend(t) }; }

    /** The centre the tube curves round, for the shader; null while it is (near enough) straight. */
    static float[] curveCentre(double t) {
        if (t >= BEND_SECONDS) return new float[] { (float) MID, (float) CIRCLE_R };
        double kappa = bend(t) / CIRCLE_R;
        return kappa < 1e-3 ? null : new float[] { (float) MID, (float) (1 / kappa) };
    }

    /**
     * Emits the tube at morph time {@code t} (negative = the plain bar, its front at
     * {@code front} of the way along).
     *
     * @param twistOffset the twist's drift (the ring's face drift)
     * @param spin        how far the finished logo's cuts have turned
     */
    static void emit(double t, double front, double twistOffset, double spin, LogoCanvas.Emitter out) {
        if (t < BEND_SECONDS) {
            double kappa = t <= 0 ? 0 : bend(t) / CIRCLE_R;
            double end = t <= 0 ? front * TOTAL : TOTAL;
            for (int k = 0; k < CARRIAGES; k++) {
                double start = k * (CAR + GAP_LEN), len = Math.min(CAR, end - start);
                if (len < 0.01) break;
                LogoRingGeometry.buildBent(kappa, MID, start, len, twistOffset, k % LogoRingGeometry.PIECES,
                        out.withSegLen((float) len));
            }
            return;
        }
        // On the circle: phi is the ring angle round (MID, 2R), the bar's middle at the bottom.
        // The twist there is (phi + pi/2) + MID/(2R) - drift, the bend's own; it eases to half that rate.
        double twist0 = MID / CIRCLE_R - twistOffset, phiRef = -Math.PI / 2;
        if (!done(t)) {
            double q = merge(t), radius = lerp(CIRCLE_R, R, q), rate = 1 - q / 2;
            for (int k = 0; k < CARRIAGES; k++) {
                double start = carriageAngle(k) + q * (k % 2 == 0 ? GAP / 4 : -GAP / 4) + spin;
                LogoRingGeometry.buildArc(MID, CIRCLE_R, radius, start, start + SEG_ANGLE / 2, rate, phiRef, twist0,
                        CIRCLE_R, k % LogoRingGeometry.PIECES, out.withSegLen((float) CAR));
            }
            return;
        }
        for (int p = 0; p < LogoRingGeometry.PIECES; p++) {
            double start = carriageAngle(2 * p) + GAP / 4 + spin;
            LogoRingGeometry.buildArc(MID, CIRCLE_R, R, start, start + SEG_ANGLE, 0.5, phiRef, twist0,
                    R, p, out.withSegLen((float) CAR));
        }
    }

    /** Carriage k's start angle on the circle of eight. */
    static double carriageAngle(int k) { return (k * (CAR + GAP_LEN) - MID) / CIRCLE_R - Math.PI / 2; }

    private static double ease(double x) {
        double c = Math.max(0, Math.min(1, x));
        return c * c * (3 - 2 * c);
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
