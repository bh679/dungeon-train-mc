package games.brennan.dungeontrain.client.logo;

import java.util.ArrayList;
import java.util.List;

/**
 * Path sampling for {@link LogoCracks}: a polyline densely sampled with its corners rounded off.
 * Each sample records how close it is to a corner (1 at the turn, 0 well away), its distance along
 * the path and its heading. A port of {@code samplePath} in the website's
 * {@code logo-ring/cracks.js}; the two are meant to stay in step.
 */
final class CrackPath {

    /** One sample along a path: position, corner closeness, distance along, unit heading. */
    record Sample(double x, double y, double corner, double dist, double dx, double dy) {}

    private CrackPath() {}

    /** Samples {@code pts} (each {x, y}) with corners rounded to {@code radius} texels. */
    static List<Sample> sample(double[][] pts, double radius) {
        List<double[]> raw = new ArrayList<>(); // x, y, corner, dist
        double[] cur = pts[0];
        push(raw, cur[0], cur[1], 0);
        for (int i = 1; i < pts.length - 1; i++) {
            double px = pts[i][0], py = pts[i][1], nx = pts[i + 1][0], ny = pts[i + 1][1];
            double l1 = Math.hypot(px - cur[0], py - cur[1]), l2 = Math.hypot(nx - px, ny - py);
            double r = Math.min(radius, Math.min(l1 / 2, l2 / 2));
            double ax = px + (cur[0] - px) * r / l1, ay = py + (cur[1] - py) * r / l1;
            double cx = px + (nx - px) * r / l2, cy = py + (ny - py) * r / l2;
            line(raw, cur[0], cur[1], ax, ay);
            int n = Math.max(4, (int) Math.ceil(r * 1.6));
            for (int j = 1; j <= n; j++) { // quadratic curve through the corner
                double t = (double) j / n, u = 1 - t;
                push(raw, u * u * ax + 2 * u * t * px + t * t * cx, u * u * ay + 2 * u * t * py + t * t * cy,
                        Math.sin(Math.PI * t));
            }
            cur = new double[] { cx, cy };
        }
        double[] last = pts[pts.length - 1];
        line(raw, cur[0], cur[1], last[0], last[1]);

        // Spread the corner swell a little either side of the turn, and record each sample's heading.
        List<Sample> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            double corner = 0;
            for (int j = Math.max(0, i - 10); j < Math.min(raw.size(), i + 11); j++) {
                corner = Math.max(corner, raw.get(j)[2] * (1 - Math.abs(i - j) / 11.0));
            }
            double[] a = raw.get(Math.max(0, i - 3)), b = raw.get(Math.min(raw.size() - 1, i + 3));
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (len == 0) len = 1;
            double[] s = raw.get(i);
            out.add(new Sample(s[0], s[1], corner, s[3], (b[0] - a[0]) / len, (b[1] - a[1]) / len));
        }
        return out;
    }

    private static void push(List<double[]> out, double x, double y, double corner) {
        double dist = 0;
        if (!out.isEmpty()) {
            double[] prev = out.get(out.size() - 1);
            dist = prev[3] + Math.hypot(x - prev[0], y - prev[1]);
        }
        out.add(new double[] { x, y, corner, dist });
    }

    private static void line(List<double[]> out, double ax, double ay, double bx, double by) {
        int n = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, by - ay)));
        for (int i = 1; i <= n; i++) push(out, ax + (bx - ax) * i / n, ay + (by - ay) * i / n, 0);
    }
}
