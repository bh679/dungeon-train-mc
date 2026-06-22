package games.brennan.dungeontrain.worldgen;

/**
 * Pure math for the <b>world disintegration band</b> — the stretch of a Dungeon
 * Train run where the world progressively breaks apart into void, reaches a
 * fully-void core where only the floating track survives, then reassembles into
 * normal terrain. Crossed once per run.
 *
 * <p>The train origin is world {@code X = 0, Z = 0} and a carriage spans
 * {@code dims.length()} blocks on X, so a carriage count maps to a world-X line:
 * {@code startX = startCarriages × carriageLength}. The band runs forward (+X)
 * only; everything at {@code X < startX} (and all backward / negative X) is
 * untouched.</p>
 *
 * <p>Band layout along +X ({@code fade} = fade span, {@code core} = full-void
 * plateau):</p>
 * <pre>
 *      startX        startX+fade   startX+fade+core  startX+2·fade+core
 *  ......|  0 → 1      |     1       |    1 → 0      |......
 *  normal| breaks apart| only tracks | reassembles  | normal resumes
 * </pre>
 *
 * <p>This class is deliberately free of any Minecraft type so it can be unit
 * tested without a NeoForge bootstrap — same convention as
 * {@code DifficultyProgression}. The {@code ChunkEvent.Load} consumer
 * ({@code WorldDisintegrationEvents}) supplies world coordinates and applies the
 * erosion via raw chunk-section writes.</p>
 */
public final class Disintegration {

    /**
     * Depth below the track bed (in blocks) over which the downward erosion bias
     * ramps from none to full {@link #DEPTH_WEIGHT}. Lower blocks dissolve first,
     * so the track's support pillars "break apart as they get lower" into the void.
     * Larger = pillars fade more slowly with depth (they survive deeper down).
     */
    static final double VERTICAL_SPAN = 96.0;

    /**
     * How much the per-block removal probability is boosted at maximum depth
     * (≥ {@link #VERTICAL_SPAN} below the bed): {@code p = ramp × (1 + DEPTH_WEIGHT)}
     * clamped to 1. With 1.0, blocks a full span below the bed erode at 2× the
     * surface rate — a gentle gradient so the pillars fade gradually rather than
     * snapping off just under the bed.
     */
    static final double DEPTH_WEIGHT = 1.0;

    /** Coarse octave cell size (blocks) — large clumps breaking off. */
    private static final int NOISE_CELL_COARSE = 8;
    /** Fine octave cell size (blocks) — ragged edges on the clumps. */
    private static final int NOISE_CELL_FINE = 3;

    private Disintegration() {}

    /** World-X line where the band begins: {@code startCarriages × carriageLength}. */
    public static long bandStartX(int startCarriages, int carriageLength) {
        return (long) startCarriages * (long) carriageLength;
    }

    /** Total band width on X: {@code 2·fade + core} (fade-in + core + fade-out). */
    public static long bandLength(int fade, int core) {
        return 2L * Math.max(0, fade) + Math.max(0, core);
    }

    /**
     * True if a chunk's X span {@code [chunkMinX, chunkMaxX]} intersects the band
     * {@code [startX, startX + bandLen)}. Used as the per-chunk fast reject.
     */
    public static boolean chunkInBand(int chunkMinX, int chunkMaxX, long startX, long bandLen) {
        return chunkMaxX >= startX && chunkMinX < startX + bandLen;
    }

    /**
     * Void intensity at a world-X column, in {@code [0, 1]}: 0 before the band,
     * a linear 0→1 ramp across the fade-in, a flat 1 across the core, a linear
     * 1→0 ramp across the fade-out, and 0 after the band. Robust to
     * {@code fade == 0} (instant edges) and {@code core == 0} (no plateau).
     */
    public static double voidRamp(int worldX, long startX, int fade, int core) {
        if (worldX < startX) return 0.0;
        int f = Math.max(0, fade);
        int c = Math.max(0, core);
        long d = (long) worldX - startX;
        if (d < f) return (double) d / f;                 // fade-in 0 → 1 (f>0 here)
        if (d < (long) f + c) return 1.0;                 // full-void core
        long e = d - ((long) f + c);
        if (e < f) return 1.0 - (double) e / f;           // fade-out 1 → 0 (f>0 here)
        return 0.0;                                       // past the band — normal terrain
    }

    /**
     * Removal probability for a block at {@code (worldX, y)} given the bed Y,
     * deriving the column ramp internally. Convenience for tests; the runtime
     * path uses {@link #removalProbabilityFromRamp} with a precomputed ramp.
     */
    public static double removalProbability(int worldX, int y, int bedY, long startX, int fade, int core) {
        return removalProbabilityFromRamp(voidRamp(worldX, startX, fade, core), y, bedY);
    }

    /**
     * Removal probability from an already-computed column {@code ramp} and the
     * block's depth below the bed. {@code ramp × (1 + depthBoost)} clamped to 1,
     * where {@code depthBoost} grows from 0 at bed level to {@link #DEPTH_WEIGHT}
     * at {@link #VERTICAL_SPAN} blocks below. Blocks above the bed get no boost.
     */
    public static double removalProbabilityFromRamp(double ramp, int y, int bedY) {
        if (ramp <= 0.0) return 0.0;
        int depth = bedY - y; // positive below the bed
        double depthBoost = clamp01((double) depth / VERTICAL_SPAN) * DEPTH_WEIGHT;
        double p = ramp * (1.0 + depthBoost);
        return p > 1.0 ? 1.0 : p;
    }

    /**
     * Deterministic, spatially-coherent erosion noise in {@code [0, 1)} for a
     * world position, seeded from the world's generation seed so it is stable
     * across reloads. Two value-noise octaves (coarse + fine) so the world breaks
     * off in clumps with ragged edges rather than salt-and-pepper. A block is
     * removed when this value is below its removal probability.
     */
    public static double coherentNoise(long seed, int x, int y, int z) {
        double coarse = valueNoise(seed, x, y, z, NOISE_CELL_COARSE);
        double fine = valueNoise(seed ^ 0x5DEECE66DL, x, y, z, NOISE_CELL_FINE);
        return 0.6 * coarse + 0.4 * fine;
    }

    // ---- internals ----------------------------------------------------------

    /** Trilinearly-interpolated value noise over a lattice of {@code cell}-sized cubes. */
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

    /** SplitMix64-style hash of a seed + lattice point → {@code [0, 1)}. */
    private static double hash01(long seed, int xi, int yi, int zi) {
        long h = seed + 0x9E3779B97F4A7C15L;
        h ^= (long) xi * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h ^= (long) yi * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (long) zi * 0xD6E8FEB86659FD93L;
        h ^= (h >>> 31);
        return (h >>> 11) * 0x1.0p-53; // top 53 bits → [0, 1)
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
