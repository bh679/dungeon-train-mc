package games.brennan.dungeontrain.client.logo;

import static games.brennan.dungeontrain.client.logo.BarMorph.CAR;
import static games.brennan.dungeontrain.client.logo.BarMorph.GAP_LEN;
import static games.brennan.dungeontrain.client.logo.BarMorph.MID;
import static games.brennan.dungeontrain.client.logo.BarMorph.ease;
import static games.brennan.dungeontrain.client.logo.LogoRingGeometry.R;

/**
 * A kept alternative finish (dev: {@code -Ddungeontrain.loadingFinish=roll}): the outer four
 * carriages fade, and the middle four roll up into the logo, left then right. Each end rolls in its
 * own half, the end carriage hooking up first with its half following; the ends dip a little
 * before they rise. The carriages glide round the curve as it forms, at the logo's own cut speed,
 * so the turning logo flows on with no stop.
 *
 * <p>The middle four and their gaps are the logo's circumference and twist at its rate, so once
 * the curve is a circle of radius R they are the logo, with nothing resized.</p>
 */
final class RollFinish implements BarMorph.Finish {

    static final RollFinish INSTANCE = new RollFinish();

    /** How long the outer four take to fade. */
    static final double FADE = 0.9;
    /** When the middle four start to bend, and when they have closed. */
    static final double BEND_FROM = 0.35, CLOSE = 3.0;
    /** The right end waits until the left is well under way, as a share of the bend. */
    private static final double RIGHT_DELAY = 0.4;
    /** The finished logo's centre lifts this high (it closes round y = R). */
    private static final double LOGO_Y = 1.05;
    private static final double HALF_CIRC = Math.PI * R;       // path length from the middle to where the ends meet

    private RollFinish() {}

    @Override
    public double spinRamp(double t) { return ease((t - BEND_FROM) / 1.2); } // gliding from the start of the bend

    @Override
    public float fadeAlpha(double t) { return (float) (1 - ease(t / FADE)); }

    @Override
    public void emitFading(double t, double drift, LogoCanvas.Emitter out) {
        if (t >= FADE) return;
        for (int k : new int[] { 0, 1, 6, 7 }) {
            LogoRingGeometry.buildBent(0, MID, k * (CAR + GAP_LEN), CAR, drift, k % LogoRingGeometry.PIECES, out.withSegLen((float) CAR));
        }
    }

    @Override
    public void emit(double t, double drift, double spin, LogoCanvas.Emitter out) {
        double p = (t - BEND_FROM) / (CLOSE - BEND_FROM);
        double glide = spin * R;                               // the cuts' travel so far, as path length
        if (p >= 1) glide %= 2 * Math.PI * R;                  // closed: the circle repeats every lap
        double lift = (LOGO_Y - R) * ease(p);
        LogoCanvas.Emitter up = segLen -> raised(out.withSegLen(segLen), lift);
        LogoRingGeometry.Curve curve = curve(Math.max(0, Math.min(1, p)));
        for (int k = 2; k <= 5; k++) {
            LogoRingGeometry.buildCurve(curve, k * (CAR + GAP_LEN) + glide, CAR, drift, k % LogoRingGeometry.PIECES,
                    up.withSegLen((float) CAR));
        }
    }

    /**
     * The four's centre line at bend progress p: curvature 1/R (the logo's) where it has bent, 0
     * where it hasn't, integrated outward from the middle of the bar (which stays put). Fully bent,
     * every point is 1/R, so it is the logo's circle.
     */
    static LogoRingGeometry.Curve curve(double p) {
        int n = 1600;
        double step = 2.5 * HALF_CIRC / n;
        double[] xs = new double[2 * n + 1], ys = new double[2 * n + 1], hs = new double[2 * n + 1];
        xs[n] = MID;
        for (int dir = -1; dir <= 1; dir += 2) {
            double x = MID, y = 0, h = 0;
            for (int i = 1; i <= n; i++) {
                double frac = Math.min(1, (i - 0.5) * step / HALF_CIRC);
                h += dir * bentness(p, dir * frac) / R * step;
                x += dir * step * Math.cos(h);
                y += dir * step * Math.sin(h);
                xs[n + dir * i] = x;
                ys[n + dir * i] = y;
                hs[n + dir * i] = h;
            }
        }
        return s -> {
            double f = (s - MID) / step + n;
            int i = (int) Math.floor(Math.max(0, Math.min(2 * n - 1, f)));
            double a = Math.max(0, Math.min(1, f - i));
            return new double[] { xs[i] + (xs[i + 1] - xs[i]) * a, ys[i] + (ys[i + 1] - ys[i]) * a, hs[i] + (hs[i + 1] - hs[i]) * a };
        };
    }

    /** How bent the curve is at x (-1 the left end, 0 the middle, 1 the right end), 0..1; 1 everywhere at p = 1. */
    static double bentness(double p, double x) {
        double rolled = halfRoll(p, x);
        double breathed = rolled - dip(p, x) * (1 - rolled);
        return hooked(breathed, p, x);
    }

    /** Each end rolls in over its own half only, the right later; sharp fronts so the end leads. Both reach the middle by p = 1. */
    private static double halfRoll(double p, double x) {
        double w = 0.45, rightSpeed = Math.max(1.95, (1 + w) / (1 - RIGHT_DELAY) + 0.05);
        return x < 0 ? ease((p * 1.5 - (x + 1)) / w) : ease(((p - RIGHT_DELAY) * rightSpeed - (1 - x)) / w);
    }

    /** The ends' anticipation dip: down a little first, gone by the time it closes. */
    private static double dip(double p, double x) {
        return 0.45 * Math.sin(Math.PI * Math.min(1, p * 1.4)) * x * x * (1 - smoother(p));
    }

    /** The end carriage hooks up first as its roll starts, the roll catching up; never past the closed ring's curvature. */
    private static double hooked(double base, double p, double x) {
        double q = x >= 0 ? p - RIGHT_DELAY : p;
        double tip = Math.exp(-Math.pow((1 - Math.abs(x)) / 0.16, 2));
        double hook = Math.sin(Math.PI * Math.max(0, Math.min(1, q * 2.2))) * Math.min(1, 1.6 * tip);
        return base + (1 - Math.max(0, base)) * Math.max(0, hook);
    }

    private static double smoother(double x) {
        double c = Math.max(0, Math.min(1, x));
        return c * c * c * (c * (c * 6 - 15) + 10);
    }

    private static LogoRingGeometry.Sink raised(LogoRingGeometry.Sink s, double dy) {
        return (x, y, z, nx, ny, nz, face, along, across, hx, hy, hz, piece, tangent) ->
                s.vertex(x, y + dy, z, nx, ny, nz, face, along, across, hx, hy, hz, piece, tangent);
    }
}
