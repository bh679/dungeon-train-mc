package games.brennan.dungeontrain.worldgen.feature;

/**
 * Pure 3D field + carve rule for the <b>big open caverns</b> inside the Nether-transition band's
 * mountains. The density raise ({@code max(base, k·(T − y))}) makes the whole mountain solid under
 * its top {@code T}, so no vanilla noise cave survives there; this carves large connected rooms back
 * out of that solid interior so the ride passes through lush / dripstone / deep-dark caves.
 *
 * <p>Field: 3-octave smoothstep value noise in {@code [0,1]} (≈56-block horizontal, ≈32-block vertical
 * base wavelength). A sample is a cavern where the field exceeds {@link #THRESHOLD}, restricted to the
 * window {@code [seaLevel + FLOOR_ABOVE_SEA, min(T − ROOF_BELOW_TOP, DECORATION_CEILING)]} with an
 * envelope that lifts the threshold to "never" over {@link #TAPER} blocks at both edges. The roof
 * margin keeps cavern ceilings below vanilla's {@code above_preliminary_surface} surface-rule gate (so
 * they stay stone, not grass) and the ceiling cap matches the {@code y ≤ 256} range of every vanilla
 * cave placed feature (glow berries, moss, dripstone clusters) — a cavern above it would be bare.</p>
 *
 * <p>Cost: the terrain wrapper runs per <em>block</em> (it sits outside the router's interpolated
 * marker), so the field is only ever evaluated at the 8 corners of the enclosing
 * {@link #CELL_W}×{@link #CELL_H}×{@link #CELL_W} noise cell and trilinearly interpolated
 * ({@link #corners} + {@link #interpolate}). The per-sample {@link #apply} and the batched
 * {@code fillArray} path in {@code NetherBandTerrainDensityFunction} share exactly these two
 * functions, so they are byte-identical. No Minecraft types — unit-testable.</p>
 */
public final class CavernNoise {

    /** Noise-cell size the field is interpolated over — matches {@code size_horizontal 1 / size_vertical 2}. */
    public static final int CELL_W = 4;
    public static final int CELL_H = 8;
    private static final int CELL_W_SHIFT = 2;
    private static final int CELL_H_SHIFT = 3;

    private static final double WAVELENGTH_XZ = 56.0;
    private static final double WAVELENGTH_Y = 32.0;
    private static final int OCTAVES = 3;
    private static final long SALT = 0x3C6EF372FE94F82BL;

    /** Field value above which a sample is cavern air (tuned for ~25–35% interior occupancy). */
    public static final double THRESHOLD = 0.56;
    /** Density magnitude per unit of field above the threshold — only the sign matters for blocks. */
    public static final double CARVE_SLOPE = 2.0;
    /** Lowest cavern sample sits this many blocks above sea level (sea water never reaches a floor). */
    public static final int FLOOR_ABOVE_SEA = 4;
    /** Highest cavern sample sits this many blocks under the mountain top (below the surface-rule gate). */
    public static final int ROOF_BELOW_TOP = 24;
    /** Vanilla cave decoration stops at y 256; nothing is carved above this. */
    public static final int DECORATION_CEILING = 250;
    /** Blocks over which the threshold eases to "never" at the window's floor and roof. */
    public static final int TAPER = 8;

    private CavernNoise() {}

    /** First world-Y that may be cavern air in a column over {@code seaLevel}. */
    public static int windowBottom(int seaLevel) {
        return seaLevel + FLOOR_ABOVE_SEA;
    }

    /** Last world-Y that may be cavern air under a mountain top {@code targetTop}. */
    public static int windowTop(double targetTop) {
        return Math.min((int) Math.floor(targetTop) - ROOF_BELOW_TOP, DECORATION_CEILING);
    }

    /**
     * Carve {@code raised} (the column's raised density at {@code (x,y,z)}) where the field says cavern,
     * else return it unchanged. Per-sample form — evaluates the 8 cell corners itself.
     */
    public static double apply(long seed, int x, int y, int z, int seaLevel, double targetTop, double raised) {
        int lo = windowBottom(seaLevel);
        int hi = windowTop(targetTop);
        if (y < lo || y > hi) return raised;
        double[] c = new double[8];
        corners(seed, x >> CELL_W_SHIFT, y >> CELL_H_SHIFT, z >> CELL_W_SHIFT, c);
        return carve(interpolate(c, x, y, z), y, lo, hi, raised);
    }

    /** Apply the window envelope + threshold to a field value {@code n} and carve {@code raised} with it. */
    public static double carve(double n, int y, int lo, int hi, double raised) {
        double env = Math.min(1.0, Math.min(y - lo, hi - y) / (double) TAPER);   // 0 at the edges → 1 inside
        double threshold = THRESHOLD + (1.0 - env);                                // > 1 at the edges: never
        double carve = CARVE_SLOPE * (threshold - n);
        return carve < 0.0 ? Math.min(raised, carve) : raised;
    }

    /** Cell coordinate (floor division by the cell size) of a block coordinate. */
    public static int cellX(int x) { return x >> CELL_W_SHIFT; }
    public static int cellY(int y) { return y >> CELL_H_SHIFT; }

    /**
     * The field at the 8 corners of noise cell {@code (cx, cy, cz)}, in {@code out[(dy << 2) | (dx << 1) | dz]}
     * order (corner {@code d} = 0/1 along each axis).
     */
    public static void corners(long seed, int cx, int cy, int cz, double[] out) {
        int x0 = cx << CELL_W_SHIFT, y0 = cy << CELL_H_SHIFT, z0 = cz << CELL_W_SHIFT;
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    out[(dy << 2) | (dx << 1) | dz] =
                            field01(seed, x0 + (dx << CELL_W_SHIFT), y0 + (dy << CELL_H_SHIFT), z0 + (dz << CELL_W_SHIFT));
                }
            }
        }
    }

    /** Trilinear interpolation of {@link #corners} output at block {@code (x, y, z)} inside that cell. */
    public static double interpolate(double[] c, int x, int y, int z) {
        double fx = (x & (CELL_W - 1)) * (1.0 / CELL_W);
        double fy = (y & (CELL_H - 1)) * (1.0 / CELL_H);
        double fz = (z & (CELL_W - 1)) * (1.0 / CELL_W);
        double y0 = lerp(lerp(c[0], c[1], fz), lerp(c[2], c[3], fz), fx);
        double y1 = lerp(lerp(c[4], c[5], fz), lerp(c[6], c[7], fz), fx);
        return lerp(y0, y1, fy);
    }

    /** Raw 3-octave field in {@code [0,1]} at a world position. */
    public static double field01(long seed, double x, double y, double z) {
        double fxz = 1.0 / WAVELENGTH_XZ;
        double fy = 1.0 / WAVELENGTH_Y;
        double amp = 1.0, sum = 0.0, max = 0.0;
        long s = seed ^ SALT;
        for (int o = 0; o < OCTAVES; o++) {
            sum += amp * valueNoise(s, x * fxz, y * fy, z * fxz);
            max += amp;
            amp *= 0.5;
            fxz *= 2.0;
            fy *= 2.0;
            s = s * 6364136223846793005L + 1442695040888963407L;
        }
        double v = sum / max;
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    // ---- internals ----------------------------------------------------------

    private static double valueNoise(long seed, double x, double y, double z) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
        double tx = smooth(x - x0), ty = smooth(y - y0), tz = smooth(z - z0);
        double c000 = hash01(seed, x0, y0, z0), c100 = hash01(seed, x0 + 1, y0, z0);
        double c010 = hash01(seed, x0, y0 + 1, z0), c110 = hash01(seed, x0 + 1, y0 + 1, z0);
        double c001 = hash01(seed, x0, y0, z0 + 1), c101 = hash01(seed, x0 + 1, y0, z0 + 1);
        double c011 = hash01(seed, x0, y0 + 1, z0 + 1), c111 = hash01(seed, x0 + 1, y0 + 1, z0 + 1);
        double y0v = lerp(lerp(c000, c100, tx), lerp(c010, c110, tx), ty);
        double y1v = lerp(lerp(c001, c101, tx), lerp(c011, c111, tx), ty);
        return lerp(y0v, y1v, tz);
    }

    private static double hash01(long seed, int xi, int yi, int zi) {
        long h = seed + 0x9E3779B97F4A7C15L;
        h ^= (long) xi * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h ^= (long) yi * 0x94D049BB133111EBL;
        h = (h ^ (h >>> 31)) * 0xD6E8FEB86659FD93L;
        h ^= (long) zi * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double smooth(double t) { return t * t * (3.0 - 2.0 * t); }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
