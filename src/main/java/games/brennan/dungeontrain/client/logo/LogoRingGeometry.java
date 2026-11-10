package games.brennan.dungeontrain.client.logo;

/**
 * Geometry for the in-game ring logo: a square-section ring with a twist, cut into segments. Only
 * the cuts move; each frame the segments are rebuilt between the cut positions, so they seem to
 * slide and twist round the ring. A port of the website's {@code logo-ring/tube.js}.
 *
 * <p>Each vertex carries its surface coordinates for the shading ({@code logo_ring.fsh}): face
 * index, distance along the segment and position across the face (the cut ends are face 4, with
 * their own square coordinates). The black outline is a slightly larger back-face shell, pushed out
 * from the tube's centre line (and past the cut at each end); that push depends on position only, so
 * neighbouring faces share it and the shell stays closed along the edges.</p>
 *
 * <p>Plain maths with no Minecraft types: {@link LogoRingRenderer} feeds the triangles to a buffer.</p>
 */
public final class LogoRingGeometry {

    /** Ring centre-line radius. */
    public static final double R = 0.72;
    /** Half side of the square cross-section. */
    public static final double HALF = 0.2;
    /** Outline shell thickness, world units. */
    public static final double OUTLINE = 0.022;
    public static final int PIECES = 4;
    /** Gap between segments (Brennan's tuned 13 degrees). */
    public static final double GAP = Math.toRadians(13);
    /** The full 180 degree twist round the ring. */
    public static final double TWIST = Math.PI;
    private static final int STEPS = 40; // sweep steps per segment

    /** Receives triangles, already wound counter-clockwise as seen from outside. */
    public interface Sink {
        /**
         * One vertex: position, flat face normal, surface coordinates (face, along, across), the
         * outline push direction, and the segment it belongs to.
         */
        void vertex(double x, double y, double z, double nx, double ny, double nz,
                    int face, double along, double across, double hx, double hy, double hz, int piece);
    }

    private LogoRingGeometry() {}

    /** A segment's length in world units: every segment is the same length. */
    public static double segmentLength() { return R * (2 * Math.PI / PIECES - GAP); }

    /** A segment's length in crack-atlas texels, so its cut ends count as edges there. */
    public static double segmentTexels() { return segmentLength() / LogoCracks.ATLAS_LEN * LogoCracks.ATLAS_W; }

    /** The twist angle of the cross-section at ring angle {@code phi} (faceOn = where a face looks straight out). */
    static double twistAt(double phi, double faceOn) { return (phi - faceOn) * TWIST / (2 * Math.PI); }

    /** Outward normal of face {@code k} at ring angle {@code phi}. */
    public static double[] faceNormal(double phi, int k, double faceOn) {
        double g = twistAt(phi, faceOn) + Math.PI / 2 + k * Math.PI / 2;
        return new double[] { Math.cos(g) * Math.cos(phi), Math.cos(g) * Math.sin(phi), Math.sin(g) };
    }

    static double[] corner(double phi, double beta) {
        double r = HALF * Math.sqrt(2), radial = Math.cos(beta) * r;
        return new double[] { (R + radial) * Math.cos(phi), (R + radial) * Math.sin(phi), Math.sin(beta) * r };
    }

    /** All segments, the cuts at {@code offset} (radians round the ring), the faces drifted to {@code faceOn}. */
    public static void build(double offset, double faceOn, Sink sink) {
        double span = 2 * Math.PI / PIECES;
        Path ring = new Ring(faceOn);
        for (int i = 0; i < PIECES; i++) {
            segment(ring, offset + i * span + GAP / 2, offset + (i + 1) * span - GAP / 2, i, sink);
        }
    }

    /**
     * The same tube laid out straight along +x (the loading bar's carriages): one segment from
     * {@code start} to {@code start + length}, twisting at the ring's rate (a half turn per ring
     * circumference) from {@code faceOn} at x = 0. Its cracks come from atlas segment {@code piece}.
     */
    public static void buildStraight(double start, double length, double faceOn, int piece, Sink sink) {
        segment(new Straight(faceOn), start, start + length, piece, sink);
    }

    /**
     * A straight segment part-way to being curled into a circle (the loading bar's finish): the
     * line is bent upward round its midpoint {@code mid} at curvature {@code kappa} (0 = straight;
     * 1 / (2R) closes eight carriages into a circle of radius 2R). Lengths along the tube are kept,
     * and it twists at the ring's rate, {@code twistOffset} turning it as a whole.
     */
    public static void buildBent(double kappa, double mid, double start, double length, double twistOffset, int piece, Sink sink) {
        segment(new Bent(kappa, mid, twistOffset), start, start + length, piece, sink);
    }

    /**
     * A segment on a circle centred at ({@code cx}, {@code cy}): ring angles {@code a0} to
     * {@code a1}, its cross-section twisting by {@code (phi - phiRef) * twistRate + twist0}, and
     * {@code alongScale} world units of surface (for the cracks and edge lines) per radian. The
     * logo ring is radius R, rate 1/2 and along scale R.
     */
    public static void buildArc(double cx, double cy, double radius, double a0, double a1, double twistRate, double phiRef,
                                double twist0, double alongScale, int piece, Sink sink) {
        segment(new Arc(cx, cy, radius, twistRate, phiRef, twist0, alongScale), a0, a1, piece, sink);
    }

    /** The gap between neighbouring segments, as a length along the tube. */
    public static double gapLength() { return GAP * R; }

    /**
     * Where the tube runs: its centre line, the frame its square cross-section turns in, and the
     * twist, all by a path parameter (ring angle for the ring, x for the straight bar).
     * {@code radial} x {@code tangent} = up, so the faces' across direction is normal x tangent
     * for both, as {@code logo_ring.vsh} assumes.
     */
    private interface Path {
        double[] centre(double u);
        /** The cross-section's in-plane axis; its other axis is world z. */
        double[] radial(double u);
        double[] tangent(double u);
        double twist(double u);
        /** Distance along the tube per unit of u. */
        double scale();
    }

    private record Ring(double faceOn) implements Path {
        public double[] centre(double phi) { return new double[] { R * Math.cos(phi), R * Math.sin(phi), 0 }; }
        public double[] radial(double phi) { return new double[] { Math.cos(phi), Math.sin(phi), 0 }; }
        public double[] tangent(double phi) { return new double[] { -Math.sin(phi), Math.cos(phi), 0 }; }
        public double twist(double phi) { return twistAt(phi, faceOn); }
        public double scale() { return R; }
    }

    private record Straight(double faceOn) implements Path {
        public double[] centre(double x) { return new double[] { x, 0, 0 }; }
        public double[] radial(double x) { return new double[] { 0, -1, 0 }; }
        public double[] tangent(double x) { return new double[] { 1, 0, 0 }; }
        public double twist(double x) { return x * TWIST / (2 * Math.PI * R) - faceOn; }
        public double scale() { return 1; }
    }

    /** A line bent round its midpoint: radial = (sin a, -cos a), so straight it is the {@link Straight} frame. */
    private record Bent(double kappa, double mid, double twistOffset) implements Path {
        private double angle(double s) { return kappa * (s - mid); }
        public double[] centre(double s) {
            if (kappa < 1e-9) return new double[] { s, 0, 0 };
            double a = angle(s);
            return new double[] { mid + Math.sin(a) / kappa, (1 - Math.cos(a)) / kappa, 0 };
        }
        public double[] radial(double s) { double a = angle(s); return new double[] { Math.sin(a), -Math.cos(a), 0 }; }
        public double[] tangent(double s) { double a = angle(s); return new double[] { Math.cos(a), Math.sin(a), 0 }; }
        public double twist(double s) { return s * TWIST / (2 * Math.PI * R) - twistOffset; }
        public double scale() { return 1; }
    }

    private record Arc(double cx, double cy, double radius, double twistRate, double phiRef, double twist0, double alongScale) implements Path {
        public double[] centre(double phi) { return new double[] { cx + radius * Math.cos(phi), cy + radius * Math.sin(phi), 0 }; }
        public double[] radial(double phi) { return new double[] { Math.cos(phi), Math.sin(phi), 0 }; }
        public double[] tangent(double phi) { return new double[] { -Math.sin(phi), Math.cos(phi), 0 }; }
        public double twist(double phi) { return (phi - phiRef) * twistRate + twist0; }
        public double scale() { return alongScale; }
    }

    private static double[] pathNormal(Path path, double u, int k) {
        double g = path.twist(u) + Math.PI / 2 + k * Math.PI / 2;
        double[] r = path.radial(u);
        return new double[] { Math.cos(g) * r[0], Math.cos(g) * r[1], Math.sin(g) };
    }

    private record V(double[] p, double[] h, int face, double along, double across) {}

    private static V vert(Path path, double u, int j, double a, double b, int face, double along, double across) {
        double beta = path.twist(u) + Math.PI / 4 + j * Math.PI / 2, r = HALF * Math.sqrt(2);
        double[] c = path.centre(u), rad = path.radial(u);
        double[] p = { c[0] + Math.cos(beta) * r * rad[0], c[1] + Math.cos(beta) * r * rad[1], Math.sin(beta) * r };
        double[] h = normalize(new double[] { p[0] - c[0], p[1] - c[1], p[2] });
        int endSign = u == a ? -1 : u == b ? 1 : 0;
        if (endSign != 0) {
            double[] t = path.tangent(u);
            h[0] += t[0] * endSign;
            h[1] += t[1] * endSign;
            h[2] += t[2] * endSign;
        }
        return new V(p, h, face, along, across);
    }

    /** One segment from path parameter a to b. Shared corners come from the same corner index and parameter, so neighbouring faces meet exactly. */
    private static void segment(Path path, double a, double b, int piece, Sink sink) {
        for (int k = 0; k < 4; k++) {
            for (int i = 0; i < STEPS; i++) {
                double f0 = ringAt(a, b, i), f1 = ringAt(a, b, i + 1), s0 = (f0 - a) * path.scale(), s1 = (f1 - a) * path.scale();
                double[] n = pathNormal(path, (f0 + f1) / 2, k);
                quad(vert(path, f0, k, a, b, k, s0, -1), vert(path, f1, k, a, b, k, s1, -1),
                        vert(path, f1, k + 1, a, b, k, s1, 1), vert(path, f0, k + 1, a, b, k, s0, 1), n, piece, sink);
            }
        }
        // The cut faces, face index 4: across = the square's own (u, v), u scaled by HALF as in tube.js.
        double[][] capUV = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } };
        for (double[] end : new double[][] { { a, -1 }, { b, 1 } }) {
            double u = end[0], sign = end[1];
            V[] c = new V[4];
            for (int k = 0; k < 4; k++) c[k] = vert(path, u, k, a, b, 4, capUV[k][0] * HALF, capUV[k][1]);
            double[] t = path.tangent(u);
            quad(c[0], c[1], c[2], c[3], new double[] { t[0] * sign, t[1] * sign, t[2] * sign }, piece, sink);
        }
    }

    private static double ringAt(double a, double b, int i) { return i == STEPS ? b : a + (b - a) * i / STEPS; }

    private static void quad(V p0, V p1, V p2, V p3, double[] n, int piece, Sink sink) {
        tri(p0, p1, p2, n, piece, sink);
        tri(p0, p2, p3, n, piece, sink);
    }

    /** Emits a triangle wound so its front faces along {@code n}. */
    private static void tri(V p, V q, V r, double[] n, int piece, Sink sink) {
        double[] e1 = sub(q.p, p.p), e2 = sub(r.p, p.p);
        double[] cr = { e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0] };
        V[] order = cr[0] * n[0] + cr[1] * n[1] + cr[2] * n[2] >= 0 ? new V[] { p, q, r } : new V[] { p, r, q };
        for (V v : order) {
            sink.vertex(v.p[0], v.p[1], v.p[2], n[0], n[1], n[2], v.face, v.along, v.across, v.h[0], v.h[1], v.h[2], piece);
        }
    }

    private static double[] sub(double[] a, double[] b) { return new double[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] }; }

    private static double[] normalize(double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[] { v[0] / l, v[1] / l, v[2] / l };
    }
}
