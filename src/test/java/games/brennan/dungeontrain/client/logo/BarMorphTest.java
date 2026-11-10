package games.brennan.dungeontrain.client.logo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The loading bar's finish has no jumps: each stage starts exactly where the last one ended. */
class BarMorphTest {

    private static final double DRIFT = 0.8, SPIN = 0.3;

    private static List<double[]> positions(double t, double front) { return positions(t, front, SPIN); }

    /** The logo's cuts only start turning as it draws in, so the spin is 0 where the circle begins. */
    private static List<double[]> positions(double t, double front, double spin) {
        List<double[]> out = new ArrayList<>();
        BarMorph.emit(t, front, DRIFT, spin, segLen -> (x, y, z, nx, ny, nz, face, along, across, hx, hy, hz, piece) ->
                out.add(new double[] { x, y, z, along }));
        return out;
    }

    private static void assertSame(List<double[]> a, List<double[]> b, double tol) {
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            for (int c = 0; c < 4; c++) assertEquals(a.get(i)[c], b.get(i)[c], tol, "vertex " + i + " component " + c);
        }
    }

    @Test
    @DisplayName("at 100% the bar before the morph is the bar at its first instant")
    void barToBend() {
        assertSame(positions(-1, 1.0), positions(0, 1.0), 1e-9);
    }

    @Test
    @DisplayName("the fully bent line is the circle of eight the draw-in starts from")
    void bendToCircle() {
        assertSame(positions(BarMorph.BEND_SECONDS - 1e-9, 1.0, 0), positions(BarMorph.BEND_SECONDS, 1.0, 0), 1e-6);
    }

    @Test
    @DisplayName("eight carriages and their gaps close exactly round a circle of radius 2R")
    void circleCloses() {
        double circumference = BarMorph.CARRIAGES * (BarMorph.CAR + BarMorph.GAP_LEN);
        assertEquals(2 * Math.PI * BarMorph.CIRCLE_R, circumference, 1e-9);
    }

    @Test
    @DisplayName("the drawn-in pairs span exactly the logo's four segments")
    void circleToLogo() {
        List<double[]> pairs = positions(BarMorph.BEND_SECONDS + BarMorph.MERGE_SECONDS - 1e-9, 1.0);
        List<double[]> logo = positions(BarMorph.BEND_SECONDS + BarMorph.MERGE_SECONDS, 1.0);
        double[] a = bounds(pairs), b = bounds(logo);
        // Within a sliver: the pairs sample each segment in two runs of steps, the logo in one.
        for (int i = 0; i < a.length; i++) assertEquals(a[i], b[i], 1e-3);
    }

    @Test
    @DisplayName("the bar fills from the left: nothing at 0%, all eight carriages at 100%")
    void fills() {
        assertTrue(positions(-1, 0).isEmpty());
        double maxX = 0;
        for (double[] p : positions(-1, 1)) maxX = Math.max(maxX, p[0]);
        assertEquals(BarMorph.TOTAL, maxX, 1e-9);
    }

    private static double[] bounds(List<double[]> pts) {
        double[] b = { Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE };
        for (double[] p : pts) {
            b[0] = Math.min(b[0], p[0]); b[1] = Math.max(b[1], p[0]);
            b[2] = Math.min(b[2], p[1]); b[3] = Math.max(b[3], p[1]);
        }
        return b;
    }
}
