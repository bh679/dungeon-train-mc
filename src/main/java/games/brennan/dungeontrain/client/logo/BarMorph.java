package games.brennan.dungeontrain.client.logo;

import static games.brennan.dungeontrain.client.logo.LogoRingGeometry.GAP;
import static games.brennan.dungeontrain.client.logo.LogoRingGeometry.R;

/**
 * The loading bar and its finish, as geometry. Eight carriages in a line on y = 0 fill with the
 * progress, the line acting as their track. Once loaded the train pulls away along
 * {@link LogoTrack}: on past the bar, up, back along the top, then spiralling in to a loop it
 * exactly fills, closing into a ring. The ring then draws in to the logo, each pair of carriages sliding together
 * into one of its four segments while the twist eases from a full turn to the logo's half turn.
 * The logo keeps turning after that.
 *
 * <p>Every stage is the same tube with the same twist at its seams, so nothing jumps: the twist
 * rides with the carriages along the track, and the ring's angles and twist start where the loop
 * left them. Plain maths; {@link LogoRingRenderer} supplies the clock and draws it.</p>
 */
final class BarMorph {

    static final int CARRIAGES = 8;
    static final double TRAVEL_SECONDS = 6.5;
    static final double MERGE_SECONDS = 1.4;

    static final double CAR = LogoRingGeometry.segmentLength();
    static final double GAP_LEN = LogoRingGeometry.gapLength();
    static final double TOTAL = CARRIAGES * CAR + (CARRIAGES - 1) * GAP_LEN;
    static final LogoTrack TRACK = new LogoTrack(TOTAL);
    private static final double SEG_ANGLE = CAR / R;

    private BarMorph() {}

    /** How far the train has driven along the track at morph time t: eased out of the bar and into the loop. */
    static double travel(double t) { return TRACK.loopStart * ease(t / TRAVEL_SECONDS); }

    /** 0 → 1 over the draw-in to the logo, eased. */
    static double merge(double t) { return ease((t - TRAVEL_SECONDS) / MERGE_SECONDS); }

    static boolean done(double t) { return t >= TRAVEL_SECONDS + MERGE_SECONDS; }

    /**
     * Emits the tube at morph time {@code t} (negative = the plain bar, its front at
     * {@code front} of the way along).
     *
     * @param twistOffset the twist's drift (the ring's face drift)
     * @param spin        how far the finished logo's cuts have turned
     */
    static void emit(double t, double front, double twistOffset, double spin, LogoCanvas.Emitter out) {
        if (t < TRAVEL_SECONDS) {
            double d = t <= 0 ? 0 : travel(t), end = t <= 0 ? front * TOTAL : TOTAL;
            for (int k = 0; k < CARRIAGES; k++) {
                double start = k * (CAR + GAP_LEN), len = Math.min(CAR, end - start);
                if (len < 0.01) break;
                LogoRingGeometry.buildTrack(TRACK, start + d, len, d, twistOffset, k % LogoRingGeometry.PIECES,
                        out.withSegLen((float) len));
            }
            return;
        }
        // In the loop: phi is the angle round its centre, the loop entered at the top (pi/2). The
        // twist there is (phi - pi/2) - drift, the track's own; it eases to half that rate.
        double cx = TRACK.loopX, cy = TRACK.loopY, phiRef = Math.PI / 2, twist0 = -twistOffset;
        if (!done(t)) {
            double q = merge(t), radius = lerp(LogoTrack.LOOP_R, R, q), rate = 1 - q / 2;
            for (int k = 0; k < CARRIAGES; k++) {
                double start = carriageAngle(k) + q * (k % 2 == 0 ? GAP / 4 : -GAP / 4) + spin;
                LogoRingGeometry.buildArc(cx, cy, radius, start, start + SEG_ANGLE / 2, rate, phiRef, twist0,
                        LogoTrack.LOOP_R, k % LogoRingGeometry.PIECES, out.withSegLen((float) CAR));
            }
            return;
        }
        for (int p = 0; p < LogoRingGeometry.PIECES; p++) {
            double start = carriageAngle(2 * p) + GAP / 4 + spin;
            LogoRingGeometry.buildArc(cx, cy, R, start, start + SEG_ANGLE, 0.5, phiRef, twist0, R, p, out.withSegLen((float) CAR));
        }
    }

    /** Carriage k's start angle once the train has closed the loop. */
    static double carriageAngle(int k) { return Math.PI / 2 + k * (CAR + GAP_LEN) / LogoTrack.LOOP_R; }

    private static double ease(double x) {
        double c = Math.max(0, Math.min(1, x));
        return c * c * (3 - 2 * c);
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
