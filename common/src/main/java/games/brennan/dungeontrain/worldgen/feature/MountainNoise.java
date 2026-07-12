package games.brennan.dungeontrain.worldgen.feature;

/**
 * A deterministic 2D <b>ridged fractal heightmap</b> in {@code [0,1]} used to give the
 * Nether-transition mountains real relief everywhere — including over naturally flat
 * terrain, where amplifying the vanilla heightmap alone would just produce a flat plane.
 *
 * <p>Ridged multifractal value noise (4 octaves, ~96-block base feature size) reads as
 * mountain ridges and valleys rather than rolling hills. Pure / unit-testable — no
 * Minecraft types, seeded from the per-world generation seed.</p>
 */
public final class MountainNoise {

    /** Base feature wavelength (blocks) of the coarsest octave. */
    private static final double BASE_WAVELENGTH = 96.0;
    private static final int OCTAVES = 4;

    private MountainNoise() {}

    /** Mountain height fraction in {@code [0,1]} at world (x, z) — 1 = a ridge crest, 0 = a valley floor. */
    public static double height01(long seed, double x, double z) {
        double freq = 1.0 / BASE_WAVELENGTH;
        double amp = 1.0;
        double sum = 0.0;
        double max = 0.0;
        long s = seed;
        for (int o = 0; o < OCTAVES; o++) {
            double n = valueNoise(s, x * freq, z * freq);   // [0,1)
            double ridge = 1.0 - Math.abs(2.0 * n - 1.0);   // [0,1], peaks along the ridge line
            ridge *= ridge;                                  // sharpen — more valley, peakier crests
            sum += amp * ridge;
            max += amp;
            amp *= 0.5;
            freq *= 2.0;
            s = s * 6364136223846793005L + 1442695040888963407L;
        }
        double v = sum / max;
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    /**
     * Smooth (non-ridged) fractal value noise in {@code [0,1]} — gentle rolling hills rather than the
     * sharp ridges of {@link #height01}. Same octaves and base wavelength, but without the ridge
     * transform, so it reads as soft dunes/swells. Used for the ocean-shore beach dunes.
     */
    public static double smooth01(long seed, double x, double z) {
        double freq = 1.0 / BASE_WAVELENGTH;
        double amp = 1.0;
        double sum = 0.0;
        double max = 0.0;
        long s = seed;
        for (int o = 0; o < OCTAVES; o++) {
            sum += amp * valueNoise(s, x * freq, z * freq);
            max += amp;
            amp *= 0.5;
            freq *= 2.0;
            s = s * 6364136223846793005L + 1442695040888963407L;
        }
        double v = sum / max;
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    // ---- internals ----------------------------------------------------------

    private static double valueNoise(long seed, double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double tx = smooth(x - x0);
        double tz = smooth(z - z0);
        double c00 = hash01(seed, x0, z0);
        double c10 = hash01(seed, x0 + 1, z0);
        double c01 = hash01(seed, x0, z0 + 1);
        double c11 = hash01(seed, x0 + 1, z0 + 1);
        double a = lerp(c00, c10, tx);
        double b = lerp(c01, c11, tx);
        return lerp(a, b, tz);
    }

    private static double hash01(long seed, int xi, int zi) {
        long h = seed + 0x9E3779B97F4A7C15L;
        h ^= (long) xi * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h ^= (long) zi * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
