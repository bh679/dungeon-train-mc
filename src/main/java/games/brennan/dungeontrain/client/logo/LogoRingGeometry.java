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
        for (int i = 0; i < PIECES; i++) {
            segment(offset + i * span + GAP / 2, offset + (i + 1) * span - GAP / 2, faceOn, i, sink);
        }
    }

    private record V(double[] p, double[] h, int face, double along, double across) {}

    private static V vert(double phi, int j, double a, double b, double faceOn, int face, double along, double across) {
        double[] p = corner(phi, twistAt(phi, faceOn) + Math.PI / 4 + j * Math.PI / 2);
        double[] h = normalize(new double[] { p[0] - R * Math.cos(phi), p[1] - R * Math.sin(phi), p[2] });
        int endSign = phi == a ? -1 : phi == b ? 1 : 0;
        if (endSign != 0) {
            h[0] += -Math.sin(phi) * endSign;
            h[1] += Math.cos(phi) * endSign;
        }
        return new V(p, h, face, along, across);
    }

    /** One segment from ring angle a to b. Shared corners come from the same corner index and angle, so neighbouring faces meet exactly. */
    private static void segment(double a, double b, double faceOn, int piece, Sink sink) {
        for (int k = 0; k < 4; k++) {
            for (int i = 0; i < STEPS; i++) {
                double f0 = ringAt(a, b, i), f1 = ringAt(a, b, i + 1), s0 = (f0 - a) * R, s1 = (f1 - a) * R;
                double[] n = faceNormal((f0 + f1) / 2, k, faceOn);
                quad(vert(f0, k, a, b, faceOn, k, s0, -1), vert(f1, k, a, b, faceOn, k, s1, -1),
                        vert(f1, k + 1, a, b, faceOn, k, s1, 1), vert(f0, k + 1, a, b, faceOn, k, s0, 1), n, piece, sink);
            }
        }
        // The cut faces, face index 4: across = the square's own (u, v), u scaled by HALF as in tube.js.
        double[][] capUV = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } };
        for (double[] end : new double[][] { { a, -1 }, { b, 1 } }) {
            double phi = end[0], sign = end[1];
            V[] c = new V[4];
            for (int k = 0; k < 4; k++) c[k] = vert(phi, k, a, b, faceOn, 4, capUV[k][0] * HALF, capUV[k][1]);
            quad(c[0], c[1], c[2], c[3], new double[] { -Math.sin(phi) * sign, Math.cos(phi) * sign, 0 }, piece, sink);
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
