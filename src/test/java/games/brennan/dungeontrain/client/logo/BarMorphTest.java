package games.brennan.dungeontrain.client.logo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static games.brennan.dungeontrain.client.logo.BarMorph.CAR;
import static games.brennan.dungeontrain.client.logo.BarMorph.GAP_LEN;
import static games.brennan.dungeontrain.client.logo.BarMorph.MID;
import static games.brennan.dungeontrain.client.logo.BarMorph.TOTAL;
import static games.brennan.dungeontrain.client.logo.BarMorph.TUBE;
import static games.brennan.dungeontrain.client.logo.LogoRingGeometry.R;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The loading bar and its finishes: the bar fills, and each finish ends as exactly the logo, never jumping or overlapping. */
class BarMorphTest {

    private static final double DRIFT = 0.8;

    @AfterEach
    void reset() { System.clearProperty("dungeontrain.loadingFinish"); }

    /** Every carriage's vertex positions at morph time t (negative t: the bar at {@code front}). */
    private static List<List<double[]>> cars(double t, double front, double spin) {
        List<List<double[]>> out = new ArrayList<>();
        BarMorph.emit(t, front, DRIFT, spin, segLen -> {
            List<double[]> car = new ArrayList<>();
            out.add(car);
            return (x, y, z, nx, ny, nz, face, along, across, hx, hy, hz, piece, tangent) -> car.add(new double[] { x, y, z, face });
        });
        return out;
    }

    private static List<double[]> all(List<List<double[]>> cars) {
        List<double[]> out = new ArrayList<>();
        cars.forEach(out::addAll);
        return out;
    }

    @Test
    @DisplayName("four carriages and their gaps are exactly the logo's circumference")
    void fourAreTheLogo() {
        assertEquals(2 * Math.PI * R, 4 * (CAR + GAP_LEN), 1e-12);
    }

    @Test
    @DisplayName("the bar fills from the left: nothing at 0%, all eight carriages at 100%")
    void fills() {
        assertTrue(cars(-1, 0, 0).isEmpty());
        assertEquals(BarMorph.CARRIAGES, cars(-1, 1, 0).size());
        double maxX = 0;
        for (double[] p : all(cars(-1, 1, 0))) maxX = Math.max(maxX, p[0]);
        assertEquals(TOTAL, maxX, 1e-9);
    }

    @Test
    @DisplayName("portal: the finish starts as the full bar, standing still while the entrance opens")
    void portalStartsAsTheBar() {
        List<double[]> bar = all(cars(-1, 1, 0)), opening = all(cars(PortalFinish.OPEN * 0.9, 1, 0));
        assertEquals(bar.size(), opening.size());
        for (int i = 0; i < bar.size(); i++) {
            for (int c = 0; c < 3; c++) assertEquals(bar.get(i)[c], opening.get(i)[c], 1e-9);
        }
        assertEquals(0, PortalFinish.travel(PortalFinish.OPEN), 1e-12);
    }

    @Test
    @DisplayName("portal: top speed, then a smooth slowdown to the logo's own speed, with no jumps")
    void portalSpeed() {
        double h = 1e-4, v = PortalFinish.LOGO_SPEED;
        assertEquals(PortalFinish.TOP_SPEED, speed(PortalFinish.OPEN + 0.5, h) / v, 1e-3);
        double settled = PortalFinish.OPEN + PortalFinish.SLOW_FROM + PortalFinish.SLOW_SECONDS + 1;
        assertEquals(1, speed(settled, h) / v, 1e-3);
        // Once moving, the speed never jumps: neighbouring samples 0.01 s apart stay within a smooth slowdown's change.
        double last = speed(PortalFinish.OPEN + 0.01, h);
        for (double t = PortalFinish.OPEN + 0.02; t < settled; t += 0.01) {
            double now = speed(t, h);
            assertTrue(Math.abs(now - last) < 0.01 * v * PortalFinish.TOP_SPEED, "speed jumps at t=" + t);
            last = now;
        }
    }

    private static double speed(double t, double h) { return (PortalFinish.travel(t + h) - PortalFinish.travel(t)) / h; }

    @Test
    @DisplayName("portal: when the last carriage is through, the last four lie round the logo's circle and nothing is left on the bar")
    void portalClosesIntoTheLogo() {
        List<List<double[]>> closed = cars(PortalFinish.CLOSE + 1e-6, 1, 0);
        assertEquals(4, closed.size(), "only the last four remain");
        for (double[] p : all(closed)) {
            double r = Math.hypot(p[0] - MID, p[1] - PortalFinish.CENTRE_Y);
            assertTrue(r > R - TUBE - 1e-6 && r < R + TUBE + 1e-6, "off the logo's ring: r=" + r);
        }
    }

    @Test
    @DisplayName("portal: the entrance grows first, the exit appears only when needed, both shrink away after")
    void portals() {
        List<BarMorph.PortalLine> opening = PortalFinish.INSTANCE.portals(PortalFinish.OPEN / 2);
        assertEquals(1, opening.size(), "only the entrance while it opens");
        assertTrue(opening.get(0).grown() > 0 && opening.get(0).grown() < 1);
        assertEquals(1, PortalFinish.INSTANCE.portals(PortalFinish.OPEN + 0.2).size(), "no exit before a carriage needs it");
        assertEquals(2, PortalFinish.INSTANCE.portals(PortalFinish.CLOSE - 0.05).size(), "both open as the last carriage goes through");
        List<BarMorph.PortalLine> shutting = PortalFinish.INSTANCE.portals(PortalFinish.CLOSE + PortalFinish.SHUT / 2);
        assertEquals(2, shutting.size());
        shutting.forEach(p -> assertTrue(p.grown() < 1, "both shrinking"));
        assertTrue(PortalFinish.INSTANCE.portals(PortalFinish.CLOSE + PortalFinish.SHUT + 0.01).isEmpty());
    }

    @Test
    @DisplayName("roll (kept alternative): the middle four close round a circle of radius R")
    void rollClosesIntoTheLogo() {
        System.setProperty("dungeontrain.loadingFinish", "roll");
        List<List<double[]>> closed = cars(RollFinish.CLOSE + 0.5, 1, 0);
        assertEquals(4, closed.size());
        double cy = 1.05;
        for (double[] p : all(closed)) {
            double r = Math.hypot(p[0] - MID, p[1] - cy);
            assertTrue(r > R - TUBE - 0.01 && r < R + TUBE + 0.01, "off the logo's ring: r=" + r);
        }
    }

    @Test
    @DisplayName("portal: no carriage ever passes through another")
    void portalNeverOverlaps() { assertNoOverlap(PortalFinish.CLOSE + 2); }

    @Test
    @DisplayName("roll: no carriage ever passes through another")
    void rollNeverOverlaps() {
        System.setProperty("dungeontrain.loadingFinish", "roll");
        assertNoOverlap(RollFinish.CLOSE + 0.2);
    }

    private static void assertNoOverlap(double until) {
        double worst = Double.MAX_VALUE;
        String where = "";
        for (double t = 0; t < until; t += 0.02) {
            List<List<double[]>> spines = new ArrayList<>();
            for (List<double[]> car : cars(t, 1, BarMorph.finish().spinRamp(t) * t)) spines.add(spine(car));
            for (int i = 0; i < spines.size(); i++) {
                for (int j = i + 1; j < spines.size(); j++) {
                    double d = closestInterior(spines.get(i), spines.get(j));
                    if (d < worst) { worst = d; where = String.format("t=%.2f cars %d,%d", t, i, j); }
                }
            }
        }
        // Two tubes overlap when their centre lines pass closer than one tube's full width.
        assertTrue(worst >= 2 * LogoRingGeometry.HALF, "carriages " + String.format("%.3f", worst) + " apart at " + where);
    }

    /** A carriage's centre line: its four long faces' quads at each sweep step, averaged. */
    private static List<double[]> spine(List<double[]> car) {
        List<double[]> quads = new ArrayList<>();
        double[] acc = new double[4];
        for (double[] v : car) {
            if (v[3] == 4) continue;
            acc[0] += v[0]; acc[1] += v[1]; acc[2] += v[2]; acc[3]++;
            if (acc[3] == 6) {
                quads.add(new double[] { acc[0] / 6, acc[1] / 6, acc[2] / 6 });
                acc[0] = acc[1] = acc[2] = acc[3] = 0;
            }
        }
        List<double[]> out = new ArrayList<>();
        int steps = quads.size() / 4;
        for (int i = 0; i < steps; i++) {
            double[] p = new double[3];
            for (int k = 0; k < 4; k++) for (int d = 0; d < 3; d++) p[d] += quads.get(k * steps + i)[d] / 4;
            out.add(p);
        }
        return out;
    }

    /** Closest approach of two centre lines, ignoring a quarter of each at either end (where neighbours meet end to end). */
    private static double closestInterior(List<double[]> a, List<double[]> b) {
        double best = Double.MAX_VALUE;
        int ta = a.size() / 4, tb = b.size() / 4;
        for (int i = ta; i < a.size() - ta; i++) {
            for (int j = tb; j < b.size() - tb; j++) {
                double[] p = a.get(i), q = b.get(j);
                best = Math.min(best, Math.sqrt((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1]) + (p[2] - q[2]) * (p[2] - q[2])));
            }
        }
        return best;
    }

    @Test
    @DisplayName("the default finish is the portal")
    void defaultIsPortal() {
        assertTrue(BarMorph.finish() instanceof PortalFinish);
        assertFalse(BarMorph.finish() instanceof RollFinish);
    }
}
