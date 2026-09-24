/*
 * Adapted from Moderner Beta (https://github.com/Nostalgica-Reverie/moderner-beta),
 * util/noise/SimplexNoise.java. Copyright (c) 2021 B3spectacled. MIT License — see THIRD_PARTY_NOTICES.md.
 * Reference: http://weber.itn.liu.se/~stegu/simplexnoise/simplexnoise.pdf
 */
package games.brennan.dungeontrain.worldgen.legacy.noise;

import java.util.Random;

/** The Beta-era 2-D simplex noise behind the climate (temperature / humidity) maps. */
public final class SimplexNoise {

    private static final int[][] GRADIENTS = {
            {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
            {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
            {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}};
    private static final double SKEW = 0.5D * (Math.sqrt(3.0D) - 1.0D);
    private static final double UNSKEW = (3.0D - Math.sqrt(3.0D)) / 6.0D;

    private final int[] permutations = new int[512];
    private final double xOrigin;
    private final double yOrigin;

    public SimplexNoise(Random random) {
        this.xOrigin = random.nextDouble() * 256.0D;
        this.yOrigin = random.nextDouble() * 256.0D;
        random.nextDouble(); // zOrigin — unused in 2-D, but the original consumed it
        for (int i = 0; i < 256; i++) {
            permutations[i] = i;
        }
        for (int i = 0; i < 256; i++) {
            int j = random.nextInt(256 - i) + i;
            int k = permutations[i];
            permutations[i] = permutations[j];
            permutations[j] = k;
            permutations[i + 256] = permutations[i];
        }
    }

    private static int fastFloor(double d) {
        return d > 0.0D ? (int) d : (int) d - 1;
    }

    private static double dot(int[] g, double x, double y) {
        return g[0] * x + g[1] * y;
    }

    public double sample(double x, double y, double scaleX, double scaleY) {
        x = x * scaleX + xOrigin;
        y = y * scaleY + yOrigin;
        double s = (x + y) * SKEW;
        int i = fastFloor(x + s);
        int j = fastFloor(y + s);
        double t = (i + j) * UNSKEW;
        double xDist = x - (i - t);
        double yDist = y - (j - t);
        int offI = xDist > yDist ? 1 : 0;
        int offJ = xDist > yDist ? 0 : 1;
        double midX = xDist - offI + UNSKEW;
        double midY = yDist - offJ + UNSKEW;
        double lastX = xDist - 1.0D + 2.0D * UNSKEW;
        double lastY = yDist - 1.0D + 2.0D * UNSKEW;
        int hi = i & 0xFF;
        int hj = j & 0xFF;
        int g0 = permutations[hi + permutations[hj]] % 12;
        int g1 = permutations[hi + offI + permutations[hj + offJ]] % 12;
        int g2 = permutations[hi + 1 + permutations[hj + 1]] % 12;
        return 70.0D * (corner(xDist, yDist, g0) + corner(midX, midY, g1) + corner(lastX, lastY, g2));
    }

    private static double corner(double x, double y, int grad) {
        double t = 0.5D - x * x - y * y;
        if (t < 0.0D) return 0.0D;
        t *= t;
        return t * t * dot(GRADIENTS[grad], x, y);
    }
}
