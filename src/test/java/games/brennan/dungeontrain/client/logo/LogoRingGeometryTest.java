package games.brennan.dungeontrain.client.logo;

import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogoRingGeometryTest {

    private record Vert(double x, double y, double z, double nx, double ny, double nz, int face, double along, double across, int piece) {}

    private static List<Vert> build(double offset, double faceOn) {
        List<Vert> out = new ArrayList<>();
        LogoRingGeometry.build(offset, faceOn, (x, y, z, nx, ny, nz, face, along, across, hx, hy, hz, piece) ->
                out.add(new Vert(x, y, z, nx, ny, nz, face, along, across, piece)));
        return out;
    }

    @Test
    @DisplayName("four segments of 4 long faces x 40 steps plus two cut ends, as triangles")
    void triangleCount() {
        List<Vert> v = build(0.3, 2.0);
        assertEquals(0, v.size() % 3);
        int perSegment = (4 * 40 * 2 + 2 * 2) * 3;
        assertEquals(LogoRingGeometry.PIECES * perSegment, v.size());
    }

    @Test
    @DisplayName("every triangle is wound counter-clockwise round its face normal")
    void winding() {
        List<Vert> v = build(1.1, -0.4);
        for (int i = 0; i < v.size(); i += 3) {
            Vert p = v.get(i), q = v.get(i + 1), r = v.get(i + 2);
            double e1x = q.x - p.x, e1y = q.y - p.y, e1z = q.z - p.z, e2x = r.x - p.x, e2y = r.y - p.y, e2z = r.z - p.z;
            double cx = e1y * e2z - e1z * e2y, cy = e1z * e2x - e1x * e2z, cz = e1x * e2y - e1y * e2x;
            assertTrue(cx * p.nx + cy * p.ny + cz * p.nz >= 0, "triangle " + i / 3);
        }
    }

    @Test
    @DisplayName("neighbouring long faces meet exactly: face k's +1 edge is face k+1's -1 edge")
    void facesShareEdges() {
        Map<String, Integer> plus = new HashMap<>(), minus = new HashMap<>();
        for (Vert v : build(0.7, 1.3)) {
            if (v.face == 4) continue;
            String key = v.piece + ":" + Math.round(v.x * 1e6) + "," + Math.round(v.y * 1e6) + "," + Math.round(v.z * 1e6);
            if (v.across > 0) plus.put(key + "@" + v.face, 1);
            else minus.put(key + "@" + ((v.face + 3) % 4), 1);
        }
        assertEquals(plus.keySet(), minus.keySet());
    }

    @Test
    @DisplayName("segments are the same length and leave the 13 degree gap between them")
    void segmentLength() {
        double max = 0;
        for (Vert v : build(0, 0)) if (v.face < 4) max = Math.max(max, v.along);
        assertEquals(LogoRingGeometry.segmentLength(), max, 1e-9);
        assertEquals(LogoRingGeometry.R * (Math.PI / 2 - Math.toRadians(13)), LogoRingGeometry.segmentLength(), 1e-12);
    }

    @Test
    @DisplayName("the across direction is normal x along, as the shader assumes")
    void bitangentIsNormalCrossTangent() {
        double phi = 0.9, faceOn = 0.4;
        for (int k = 0; k < 4; k++) {
            double[] n = LogoRingGeometry.faceNormal(phi, k, faceOn);
            double beta = LogoRingGeometry.twistAt(phi, faceOn) + Math.PI / 4;
            double[] c0 = LogoRingGeometry.corner(phi, beta + k * Math.PI / 2), c1 = LogoRingGeometry.corner(phi, beta + (k + 1) * Math.PI / 2);
            double[] b = { c1[0] - c0[0], c1[1] - c0[1], c1[2] - c0[2] };
            double[] t = { -Math.sin(phi), Math.cos(phi), 0 };
            double[] nxt = { n[1] * t[2] - n[2] * t[1], n[2] * t[0] - n[0] * t[2], n[0] * t[1] - n[1] * t[0] };
            double dot = (nxt[0] * b[0] + nxt[1] * b[1] + nxt[2] * b[2]) / Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
            assertEquals(1, dot, 1e-9, "face " + k);
        }
    }

    @Test
    @DisplayName("face visibility primes quietly, then reports only faces that newly went out of view")
    void visibility() {
        FaceVisibility vis = new FaceVisibility(new Vector3f(0.4f, -1.6f, 4.6f));
        int[] first = vis.newlyHidden(0, 2.3);
        for (int m : first) assertEquals(0, m);
        int hiddenTotal = 0;
        for (int p = 0; p < 4; p++) hiddenTotal += Integer.bitCount(vis.hidden(p));
        assertTrue(hiddenTotal > 0, "some faces face away from the camera");
        int[] same = vis.newlyHidden(0, 2.3);
        for (int m : same) assertEquals(0, m);
    }

    @Test
    @DisplayName("the placement property defaults to the background and ignores junk")
    void placement() {
        assertEquals(LogoRingPlacement.Title.BACKGROUND, LogoRingPlacement.parse(null));
        assertEquals(LogoRingPlacement.Title.WORDMARK, LogoRingPlacement.parse(" wordmark "));
        assertEquals(LogoRingPlacement.Title.OFF, LogoRingPlacement.parse("off"));
        assertEquals(LogoRingPlacement.Title.BACKGROUND, LogoRingPlacement.parse("sideways"));
    }
}
