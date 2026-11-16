package games.brennan.dungeontrain.client.logo;

import org.joml.Vector3f;

/**
 * Which of each segment's long faces the camera can't see, frame to frame: a face is hidden when it
 * faces away from the camera all along its segment (checked at a few points, since the twist turns
 * it as it goes). {@link LogoRingRenderer} regrows a segment's hidden faces the moment one of them
 * goes out of view, as the website's {@code logo-ring.js} does.
 */
final class FaceVisibility {

    private static final int SAMPLES = 8;

    private final double cx, cy, cz;
    private final int[] hidden = new int[LogoRingGeometry.PIECES];
    private boolean primed;

    FaceVisibility(Vector3f camera) {
        this.cx = camera.x;
        this.cy = camera.y;
        this.cz = camera.z;
    }

    /** Face mask (bit k = face k) hidden on {@code piece} as of the last update. */
    int hidden(int piece) { return hidden[piece]; }

    void reset() { primed = false; }

    /**
     * Updates the hidden masks and returns, per segment, the faces that have just gone out of view
     * (all zero on the first call, which only primes the state).
     */
    int[] newlyHidden(double offset, double faceOn) {
        int[] fresh = new int[LogoRingGeometry.PIECES];
        for (int piece = 0; piece < fresh.length; piece++) {
            int now = hiddenFaces(piece, offset, faceOn);
            if (primed) fresh[piece] = now & ~hidden[piece];
            hidden[piece] = now;
        }
        primed = true;
        return fresh;
    }

    int hiddenFaces(int piece, double offset, double faceOn) {
        double span = 2 * Math.PI / LogoRingGeometry.PIECES;
        double a = offset + piece * span + LogoRingGeometry.GAP / 2, b = offset + (piece + 1) * span - LogoRingGeometry.GAP / 2;
        int mask = 0;
        for (int k = 0; k < 4; k++) {
            boolean seen = false;
            for (int j = 0; j <= SAMPLES && !seen; j++) {
                double phi = a + (b - a) * j / SAMPLES;
                double[] n = LogoRingGeometry.faceNormal(phi, k, faceOn);
                double px = LogoRingGeometry.R * Math.cos(phi) + n[0] * LogoRingGeometry.HALF;
                double py = LogoRingGeometry.R * Math.sin(phi) + n[1] * LogoRingGeometry.HALF;
                double pz = n[2] * LogoRingGeometry.HALF;
                double vx = cx - px, vy = cy - py, vz = cz - pz, len = Math.sqrt(vx * vx + vy * vy + vz * vz);
                if ((n[0] * vx + n[1] * vy + n[2] * vz) / len > -0.02) seen = true;
            }
            if (!seen) mask |= 1 << k;
        }
        return mask;
    }
}
