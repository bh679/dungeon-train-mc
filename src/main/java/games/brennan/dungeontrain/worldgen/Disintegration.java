package games.brennan.dungeontrain.worldgen;

/**
 * Pure math for the <b>world disintegration band</b> — a once-per-run journey the
 * train crosses where the overworld breaks apart and reforms as the End and back:
 *
 * <pre>
 *   Overworld → Void → End world-gen → Void → Overworld
 * </pre>
 *
 * <p>The train origin is world {@code X = 0, Z = 0} and a carriage spans
 * {@code dims.length()} blocks on X, so a carriage count maps to a world-X line:
 * {@code startX = startCarriages × carriageLength}. The band runs forward (+X)
 * only.</p>
 *
 * <p>Two trapezoidal ramps drive everything, both over {@code offset d = X − startX}
 * with spans {@code F} (fade), {@code Vh} (void hold) and {@code Eh} (End hold):</p>
 * <pre>
 *   | F  | Vh |  F  |   Eh    |  F  | Vh | F  |
 *   |OW→V| V  |V→End|  End    |End→V| V  |V→OW|
 *   middleRamp M:  /‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾\        (erosion intensity + End-sky/fog)
 *   endRamp    E:        /‾‾‾‾‾‾‾‾‾‾\               (End-terrain fill intensity)
 * </pre>
 *
 * <ul>
 *   <li>{@link #middleRamp} ramps 0→1 over the first fade, holds 1 across the whole
 *       void+End+void middle, ramps 1→0 over the last fade. It drives how much
 *       overworld/End terrain is eroded <i>and</i> how far the sky/fog fade to the
 *       End look.</li>
 *   <li>{@link #endRamp} is 0 in the void holds, ramps 0→1 as the void fades into
 *       End world-gen, holds 1 across the End core, ramps back to 0. It drives how
 *       much floating End-stone island terrain is placed.</li>
 * </ul>
 *
 * <p>No Minecraft types here, so it is unit tested without a NeoForge bootstrap —
 * same convention as {@code DifficultyProgression}.</p>
 */
public final class Disintegration {

    /**
     * Depth below the track bed (blocks) over which the downward erosion bias ramps
     * from none to full {@link #DEPTH_WEIGHT}. Lower blocks dissolve first, so the
     * track's support pillars break apart as they descend. Larger = pillars fade
     * more slowly with depth.
     */
    static final double VERTICAL_SPAN = 96.0;

    /** Max extra erosion at depth: {@code p = ramp × (1 + DEPTH_WEIGHT)} clamped to 1. */
    static final double DEPTH_WEIGHT = 1.0;

    /** Coarse erosion-noise cell (blocks) — large clumps breaking off. */
    private static final int NOISE_CELL_COARSE = 8;
    /** Fine erosion-noise cell (blocks) — ragged edges on the clumps. */
    private static final int NOISE_CELL_FINE = 3;

    private Disintegration() {}

    /** World-X line where the band begins: {@code startCarriages × carriageLength}. */
    public static long bandStartX(int startCarriages, int carriageLength) {
        return (long) startCarriages * (long) carriageLength;
    }

    /** Total band width on X: {@code 4·fade + 2·voidHold + endHold}. */
    public static long bandLength(int fade, int voidHold, int endHold) {
        return 4L * Math.max(0, fade) + 2L * Math.max(0, voidHold) + Math.max(0, endHold);
    }

    /** True if a chunk's X span {@code [chunkMinX, chunkMaxX]} intersects {@code [startX, startX+bandLen)}. */
    public static boolean chunkInBand(int chunkMinX, int chunkMaxX, long startX, long bandLen) {
        return chunkMaxX >= startX && chunkMinX < startX + bandLen;
    }

    /**
     * Middle ramp {@code M ∈ [0,1]}: 0 before the band, linear 0→1 across the first
     * fade, flat 1 across the entire void+End+void middle, linear 1→0 across the
     * last fade, 0 after. Drives erosion intensity and the End sky/fog blend.
     */
    public static double middleRamp(int worldX, long startX, int fade, int voidHold, int endHold) {
        if (worldX < startX) return 0.0;
        int f = Math.max(0, fade);
        long band = bandLength(fade, voidHold, endHold);
        long d = (long) worldX - startX;
        if (d >= band) return 0.0;
        if (d < f) return (double) d / f;                 // fade-in (f>0 here)
        long holdEnd = band - f;                          // start of the fade-out
        if (d < holdEnd) return 1.0;                       // void+End+void plateau
        return 1.0 - (double) (d - holdEnd) / f;          // fade-out (f>0 here)
    }

    /**
     * End ramp {@code E ∈ [0,1]}: 0 through the void holds, linear 0→1 as the void
     * fades into End world-gen, flat 1 across the End core, linear 1→0 back to void.
     * Drives how much floating End-stone island terrain is placed.
     */
    public static double endRamp(int worldX, long startX, int fade, int voidHold, int endHold) {
        if (worldX < startX) return 0.0;
        int f = Math.max(0, fade);
        int vh = Math.max(0, voidHold);
        int eh = Math.max(0, endHold);
        long d = (long) worldX - startX;
        long a2 = (long) f + vh;          // void→End begins
        long a3 = 2L * f + vh;            // End core begins
        long a4 = 2L * f + vh + eh;       // End core ends
        long a5 = 3L * f + vh + eh;       // End→void ends
        if (d < a2 || d >= a5) return 0.0;
        if (d < a3) return (double) (d - a2) / f;         // ramp up (f>0 here)
        if (d < a4) return 1.0;                            // End core
        return 1.0 - (double) (d - a4) / f;               // ramp down (f>0 here)
    }

    /** Removal probability at {@code (worldX, y)}; derives the column middle-ramp internally (test convenience). */
    public static double removalProbability(int worldX, int y, int bedY, long startX, int fade, int voidHold, int endHold) {
        return removalProbabilityFromRamp(middleRamp(worldX, startX, fade, voidHold, endHold), y, bedY);
    }

    /**
     * Removal probability from an already-computed column middle-ramp and the block's
     * depth below the bed: {@code ramp × (1 + depthBoost)} clamped to 1, where
     * {@code depthBoost} grows 0→{@link #DEPTH_WEIGHT} over {@link #VERTICAL_SPAN}
     * blocks of depth. Blocks above the bed get no boost.
     */
    public static double removalProbabilityFromRamp(double ramp, int y, int bedY) {
        if (ramp <= 0.0) return 0.0;
        int depth = bedY - y;
        double depthBoost = clamp01((double) depth / VERTICAL_SPAN) * DEPTH_WEIGHT;
        double p = ramp * (1.0 + depthBoost);
        return p > 1.0 ? 1.0 : p;
    }

    /**
     * Minimum real-End-density value required to place an island block at the given
     * band {@code endRamp}: 0 in the End core ({@code endRamp == 1}, full islands),
     * rising toward {@link #ISLAND_EDGE_DENSITY} as the End fades into the void holds
     * ({@code endRamp → 0}), so only the densest island cores survive at the edges and
     * the End terrain dissolves smoothly into void. Pure so it is unit-testable; the
     * density itself comes from the real End noise router.
     */
    public static double islandDensityThreshold(double endRamp) {
        return (1.0 - clamp01(endRamp)) * ISLAND_EDGE_DENSITY;
    }

    /** Density threshold at the very edge of the End band (endRamp → 0) — only dense cores remain. */
    public static final double ISLAND_EDGE_DENSITY = 0.15;

    /** Deterministic, clumpy erosion noise in {@code [0,1)} (two octaves), seeded from the world seed. */
    public static double coherentNoise(long seed, int x, int y, int z) {
        double coarse = valueNoise(seed, x, y, z, NOISE_CELL_COARSE);
        double fine = valueNoise(seed ^ 0x5DEECE66DL, x, y, z, NOISE_CELL_FINE);
        return 0.6 * coarse + 0.4 * fine;
    }

    // ---- internals ----------------------------------------------------------

    private static double valueNoise(long seed, int x, int y, int z, int cell) {
        int cx = Math.floorDiv(x, cell);
        int cy = Math.floorDiv(y, cell);
        int cz = Math.floorDiv(z, cell);
        double tx = smooth((double) (x - cx * cell) / cell);
        double ty = smooth((double) (y - cy * cell) / cell);
        double tz = smooth((double) (z - cz * cell) / cell);

        double c000 = hash01(seed, cx, cy, cz);
        double c100 = hash01(seed, cx + 1, cy, cz);
        double c010 = hash01(seed, cx, cy + 1, cz);
        double c110 = hash01(seed, cx + 1, cy + 1, cz);
        double c001 = hash01(seed, cx, cy, cz + 1);
        double c101 = hash01(seed, cx + 1, cy, cz + 1);
        double c011 = hash01(seed, cx, cy + 1, cz + 1);
        double c111 = hash01(seed, cx + 1, cy + 1, cz + 1);

        double x00 = lerp(c000, c100, tx);
        double x10 = lerp(c010, c110, tx);
        double x01 = lerp(c001, c101, tx);
        double x11 = lerp(c011, c111, tx);
        double y0 = lerp(x00, x10, ty);
        double y1 = lerp(x01, x11, ty);
        return lerp(y0, y1, tz);
    }

    private static double hash01(long seed, int xi, int yi, int zi) {
        long h = seed + 0x9E3779B97F4A7C15L;
        h ^= (long) xi * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h ^= (long) yi * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (long) zi * 0xD6E8FEB86659FD93L;
        h ^= (h >>> 31);
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }
}
