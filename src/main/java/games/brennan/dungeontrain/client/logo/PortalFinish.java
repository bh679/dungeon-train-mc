package games.brennan.dungeontrain.client.logo;

import java.util.ArrayList;
import java.util.List;

import static games.brennan.dungeontrain.client.logo.BarMorph.CAR;
import static games.brennan.dungeontrain.client.logo.BarMorph.CARRIAGES;
import static games.brennan.dungeontrain.client.logo.BarMorph.GAP_LEN;
import static games.brennan.dungeontrain.client.logo.BarMorph.MID;
import static games.brennan.dungeontrain.client.logo.BarMorph.TOTAL;
import static games.brennan.dungeontrain.client.logo.BarMorph.TUBE;
import static games.brennan.dungeontrain.client.logo.BarMorph.ease;
import static games.brennan.dungeontrain.client.logo.LogoRingGeometry.R;

/**
 * The loading bar's finish: a portal grows up just past the end of the bar, and the whole train
 * drives into it. The front four go through and are gone; the last four, as they go in, come out
 * of a second portal at the bottom of a circle the logo's size and drive round it. Four carriages
 * and their gaps are exactly its circumference, so as the last one comes through the front meets
 * the back: they are the logo, and they keep circling as its turning. Both portals then close.
 *
 * <p>Speed: the train waits for the entrance to open, drives at {@link #TOP_SPEED} times the
 * logo's own speed, and from {@link #SLOW_FROM} seconds in eases down over {@link #SLOW_SECONDS} to
 * the logo's speed (a smoothstep, so the slowdown neither starts nor ends with a jolt). The twist
 * runs on across the portal, as if the two openings were one place.</p>
 */
final class PortalFinish implements BarMorph.Finish {

    static final PortalFinish INSTANCE = new PortalFinish();

    /** How long the entrance takes to grow up from the bar's line; the train waits for it. */
    static final double OPEN = 0.6;
    /** How long both portals take to shrink away once the last carriage is through. */
    static final double SHUT = 0.4;
    /** The train's top speed, as a multiple of the logo's own. */
    static final double TOP_SPEED = 5;
    /** When the slowdown starts, seconds after the train starts moving. */
    static final double SLOW_FROM = 1.0;
    /** How long the slowdown takes to ease from the top speed down to the logo's own. */
    static final double SLOW_SECONDS = 20;
    /** The logo's own speed: its cuts' 30 degrees a second, as path length round radius R. */
    static final double LOGO_SPEED = Math.toRadians(30) * R;

    /** The entrance, just past the bar's end. */
    static final double ENTRANCE = TOTAL + 0.25;
    /** The logo's centre: over the bar's middle, clear above the bar. */
    static final double CENTRE_Y = R + 2 * TUBE + 0.05;
    /** A portal's height: the tube's full height and a little more. */
    static final double PORTAL_HEIGHT = 2 * TUBE + 0.25;
    /** The last four come out of the exit at the bottom of the logo's circle, heading right as they went in. */
    private static final double EXIT_ANGLE = -Math.PI / 2;

    /** When the last carriage is through and the logo has closed, seconds after the bar read 100%. */
    static final double CLOSE = closeTime();

    private PortalFinish() {}

    /**
     * How far the train has driven at morph time t: nothing while the entrance opens, then the top
     * speed until {@link #SLOW_FROM}, then speed top - (top - 1) S(tau / 20) (S the smoothstep)
     * integrated, then the logo's own speed.
     */
    static double travel(double t) {
        double moving = t - OPEN;
        if (moving <= 0) return 0;
        if (moving <= SLOW_FROM) return TOP_SPEED * LOGO_SPEED * moving;
        double tau = moving - SLOW_FROM, x = Math.min(1, tau / SLOW_SECONDS);
        double slowed = TOP_SPEED * Math.min(tau, SLOW_SECONDS) - (TOP_SPEED - 1) * SLOW_SECONDS * (x * x * x - x * x * x * x / 2);
        return LOGO_SPEED * (TOP_SPEED * SLOW_FROM + slowed + Math.max(0, tau - SLOW_SECONDS));
    }

    private static double closeTime() {
        double lo = 0, hi = 120;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (travel(mid) >= ENTRANCE) hi = mid; else lo = mid;
        }
        return hi;
    }

    @Override
    public double spinRamp(double t) { return 0; } // the carriages' own travel is the logo's turning

    @Override
    public void emit(double t, double drift, double spin, LogoCanvas.Emitter out) {
        double d = travel(t), lap = 2 * Math.PI * R;
        // The twist runs on across the portal: what goes in at the entrance comes out at the exit.
        double twist0 = ENTRANCE / (2 * R) - drift;
        for (int k = 0; k < CARRIAGES; k++) {
            double s0 = k * (CAR + GAP_LEN) + d, s1 = s0 + CAR;
            int piece = k % LogoRingGeometry.PIECES;
            if (s0 < ENTRANCE) {                               // still (partly) on the bar
                double len = Math.min(s1, ENTRANCE) - s0;
                LogoRingGeometry.buildBent(0, MID, s0, len, drift, piece, out.withSegLen((float) len));
            }
            if (k < LogoRingGeometry.PIECES && s1 > ENTRANCE) { // the last four, (partly) out round the logo
                double u0 = Math.max(0, s0 - ENTRANCE), u1 = s1 - ENTRANCE;
                double a = EXIT_ANGLE + u0 / R, b = EXIT_ANGLE + u1 / R;
                // Once closed they keep circling; whole laps come off the angle (half a turn of twist
                // per lap swaps pale faces with pale, so it looks the same).
                double laps = Math.floor(u0 / lap) * 2 * Math.PI;
                LogoRingGeometry.buildArc(MID, CENTRE_Y, R, a - laps, b - laps, 0.5, EXIT_ANGLE, twist0, R, piece,
                        out.withSegLen((float) ((b - a) * R)));
            }
        }
    }

    @Override
    public List<BarMorph.PortalLine> portals(double t) {
        List<BarMorph.PortalLine> out = new ArrayList<>();
        double d = travel(t);
        // Both shrink away together once the last carriage is through.
        double closing = t < CLOSE ? 1 : 1 - ease((t - CLOSE) / SHUT);
        // The entrance grows up from the bar's line before the train moves.
        double entrance = Math.min(ease(t / OPEN), closing);
        if (entrance > 0) out.add(new BarMorph.PortalLine(ENTRANCE, 0, PORTAL_HEIGHT, entrance));
        // The exit just appears, as the first of the last four is about to come out.
        double firstOut = ENTRANCE - (3 * (CAR + GAP_LEN) + CAR);
        if (d >= firstOut && closing > 0) out.add(new BarMorph.PortalLine(MID, CENTRE_Y - R, PORTAL_HEIGHT, closing));
        return out;
    }
}
