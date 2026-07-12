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

    /** Width of one active band (the void+End+void with its fades): {@code 4·fade + 2·voidHold + endHold}. */
    public static long bandLength(int fade, int voidHold, int endHold) {
        return 4L * Math.max(0, fade) + 2L * Math.max(0, voidHold) + Math.max(0, endHold);
    }

    /**
     * Length of one full repeating cycle: an active band followed by an overworld
     * stretch — {@code bandLength + owHold}. The pattern tiles this period forever
     * along +X from {@code startX}.
     */
    public static long cyclePeriod(int fade, int voidHold, int endHold, int owHold) {
        return bandLength(fade, voidHold, endHold) + Math.max(0, owHold);
    }

    /**
     * Offset into the current cycle for a world-X (measured in blocks from {@code startX},
     * which is a fixed block distance from spawn — not a train metric): {@code (worldX −
     * startX + phaseShift) mod period}, or {@code -1} before {@code startX}. Each cycle is
     * {@code [0, owHold)} = the overworld phase, then {@code [owHold, period)} = the
     * active band (void → End → void). With {@code phaseShift = 0} the pattern starts every
     * cycle in the overworld at {@code startX}; a positive {@code phaseShift} lands {@code
     * startX} that many blocks into the cycle, so the first overworld stretch past the anchor
     * is shorter (the player spawns partway through it). The {@code worldX < startX} region is
     * always pure overworld regardless of the shift.
     */
    private static long cycleOffset(int worldX, long startX, int phaseShift, int fade, int voidHold, int endHold, int owHold) {
        if (worldX < startX) return -1L;
        long period = cyclePeriod(fade, voidHold, endHold, owHold);
        if (period <= 0L) return -1L;
        return Math.floorMod((long) worldX - startX + phaseShift, period);
    }

    /**
     * Middle ramp {@code M ∈ [0,1]}, evaluated on the repeating cycle: 0 across the
     * overworld phase, linear 0→1 as the overworld fades into void, flat 1 across the
     * whole void+End+void middle, linear 1→0 as void fades back to overworld. Drives
     * erosion intensity and the End sky/fog blend. Repeats forever from {@code startX}.
     */
    public static double middleRamp(int worldX, long startX, int fade, int voidHold, int endHold, int owHold) {
        return middleRamp(worldX, startX, 0, fade, voidHold, endHold, owHold);
    }

    /** {@link #middleRamp(int, long, int, int, int, int)} with a {@code phaseShift} into the cycle (see {@link #cycleOffset}). */
    public static double middleRamp(int worldX, long startX, int phaseShift, int fade, int voidHold, int endHold, int owHold) {
        long d = cycleOffset(worldX, startX, phaseShift, fade, voidHold, endHold, owHold);
        if (d < 0L) return 0.0;
        int oh = Math.max(0, owHold);
        if (d < oh) return 0.0;                            // overworld phase (start of each cycle)
        long dd = d - oh;                                  // offset within the active band
        long band = bandLength(fade, voidHold, endHold);
        int f = Math.max(0, fade);
        if (dd < f) return (double) dd / f;                // overworld → void
        long holdEnd = band - f;
        if (dd < holdEnd) return 1.0;                       // void + End + void
        return 1.0 - (double) (dd - holdEnd) / f;          // void → overworld
    }

    /**
     * True iff EVERY column of the 16-wide chunk starting at {@code chunkMinX} is fully eroded
     * ({@link #middleRamp} ≥ 1) — i.e. the overworld terrain there would be 100% removed by the
     * post-process erosion, so generating it is pure waste and the noise fill can be skipped
     * entirely. This covers the void holds, the End core, and the void↔End transitions (all held at
     * {@code middleRamp == 1}). A chunk with any fade column ({@code middleRamp < 1}) returns false
     * and keeps real overworld terrain so the erosion gradient stays smooth.
     *
     * <p>Used by both the {@code fillFromNoise} short-circuit (skip noise generation) and
     * {@code TrackBedFeature} (skip support pillars — on empty terrain the ground probe would build
     * full-height pillars to bedrock, and they'd be fully eroded anyway). Pure / unit-testable.</p>
     */
    public static boolean isChunkFullyEroded(int chunkMinX, long startX, int fade, int voidHold, int endHold, int owHold) {
        return isChunkFullyEroded(chunkMinX, startX, 0, fade, voidHold, endHold, owHold);
    }

    /** {@link #isChunkFullyEroded(int, long, int, int, int, int)} with a {@code phaseShift} into the cycle (see {@link #cycleOffset}). */
    public static boolean isChunkFullyEroded(int chunkMinX, long startX, int phaseShift, int fade, int voidHold, int endHold, int owHold) {
        for (int dx = 0; dx < 16; dx++) {
            if (middleRamp(chunkMinX + dx, startX, phaseShift, fade, voidHold, endHold, owHold) < 1.0) return false;
        }
        return true;
    }

    /**
     * Sky ramp {@code S ∈ [0,1]} — the {@link #middleRamp} shape but with both fades
     * pushed inward toward the void core by {@code skyOffset} blocks, so the End sky/fog
     * lags the terrain erosion: the fade-in is delayed past the overworld→void crumble,
     * and the fade-out finishes before the void→overworld terrain has fully reformed.
     * Drives only the client sky/fog/cloud look — terrain erosion still uses
     * {@link #middleRamp}.
     *
     * <p>Structure over the active-band offset {@code dd}, with effective offset {@code o}:
     * 0 across {@code [0, o)}, linear 0→1 over {@code [o, o+fade)}, flat 1 across the
     * middle, linear 1→0 over {@code [band−fade−o, band−o)}, then 0 again across the last
     * {@code o}. {@code skyOffset} is clamped to {@code (band − 2·fade) / 2} so the flat
     * hold can shrink to zero but never invert; {@code skyOffset == 0} reproduces
     * {@link #middleRamp} exactly.</p>
     */
    public static double skyRamp(int worldX, long startX, int fade, int voidHold, int endHold, int owHold, int skyOffset) {
        return skyRamp(worldX, startX, 0, fade, voidHold, endHold, owHold, skyOffset);
    }

    /** {@link #skyRamp(int, long, int, int, int, int, int)} with a {@code phaseShift} into the cycle (see {@link #cycleOffset}). */
    public static double skyRamp(int worldX, long startX, int phaseShift, int fade, int voidHold, int endHold, int owHold, int skyOffset) {
        long d = cycleOffset(worldX, startX, phaseShift, fade, voidHold, endHold, owHold);
        if (d < 0L) return 0.0;
        int oh = Math.max(0, owHold);
        if (d < oh) return 0.0;                            // overworld phase (start of each cycle)
        long dd = d - oh;                                  // offset within the active band
        long band = bandLength(fade, voidHold, endHold);
        int f = Math.max(0, fade);
        long maxOffset = Math.max(0L, (band - 2L * f) / 2L);
        long o = Math.min(Math.max(0, skyOffset), maxOffset);
        if (dd < o) return 0.0;                            // delayed: still overworld while ground erodes
        long riseEnd = o + f;
        if (dd < riseEnd) return f == 0 ? 1.0 : (double) (dd - o) / f;   // overworld → void (delayed)
        long fallStart = band - f - o;
        if (dd < fallStart) return 1.0;                     // void + End + void
        long fallEnd = band - o;
        if (dd < fallEnd) return f == 0 ? 0.0 : 1.0 - (double) (dd - fallStart) / f; // void → overworld (early)
        return 0.0;                                         // returned to overworld before band end
    }

    /**
     * End ramp {@code E ∈ [0,1]}, evaluated on the repeating cycle: 0 through the
     * overworld + void holds, linear 0→1 as the void fades into End world-gen, flat 1
     * across the End core, linear 1→0 back to void. Drives End-stone island fill.
     */
    public static double endRamp(int worldX, long startX, int fade, int voidHold, int endHold, int owHold) {
        return endRamp(worldX, startX, 0, fade, voidHold, endHold, owHold);
    }

    /** {@link #endRamp(int, long, int, int, int, int)} with a {@code phaseShift} into the cycle (see {@link #cycleOffset}). */
    public static double endRamp(int worldX, long startX, int phaseShift, int fade, int voidHold, int endHold, int owHold) {
        long d = cycleOffset(worldX, startX, phaseShift, fade, voidHold, endHold, owHold);
        if (d < 0L) return 0.0;
        int oh = Math.max(0, owHold);
        if (d < oh) return 0.0;                            // overworld phase
        long dd = d - oh;                                  // offset within the active band
        int f = Math.max(0, fade);
        int vh = Math.max(0, voidHold);
        int eh = Math.max(0, endHold);
        long a2 = (long) f + vh;          // void→End begins
        long a3 = 2L * f + vh;            // End core begins
        long a4 = 2L * f + vh + eh;       // End core ends
        long a5 = 3L * f + vh + eh;       // End→void ends
        if (dd < a2 || dd >= a5) return 0.0;
        if (dd < a3) return (double) (dd - a2) / f;       // ramp up (f>0 here)
        if (dd < a4) return 1.0;                           // End core
        return 1.0 - (double) (dd - a4) / f;              // ramp down (f>0 here)
    }

    /**
     * Minimum {@link #middleRamp} that counts as the genuine void: the flat void holds sit at
     * exactly {@code 1.0}, kept just below 1 for float safety. The OW↔void fades
     * ({@code middleRamp < 1}) read as overworld, so "reached the void" means truly out over
     * nothing — not merely on crumbling ground.
     */
    public static final double VOID_RAMP_MIN = 0.999;

    /** Which band a column belongs to, classified from its two ramps. */
    public enum Zone { OVERWORLD, VOID, END_ISLANDS }

    /**
     * Classify a column from its {@link #middleRamp} and {@link #endRamp}: any End-island fill ⇒
     * {@link Zone#END_ISLANDS}; otherwise a fully-held middle ramp ⇒ {@link Zone#VOID} (no
     * ground); else {@link Zone#OVERWORLD} (including the fades). Order matters — the End core also
     * holds {@code middleRamp == 1}, so {@code endRamp} is tested first. Pure / unit-testable.
     */
    public static Zone zoneOf(double middleRamp, double endRamp) {
        if (endRamp > 0.0) return Zone.END_ISLANDS;
        if (middleRamp >= VOID_RAMP_MIN) return Zone.VOID;
        return Zone.OVERWORLD;
    }

    /** Removal probability at {@code (worldX, y)}; derives the column middle-ramp internally (test convenience). */
    public static double removalProbability(int worldX, int y, int bedY, long startX, int fade, int voidHold, int endHold, int owHold) {
        return removalProbability(worldX, y, bedY, startX, 0, fade, voidHold, endHold, owHold);
    }

    /** {@link #removalProbability(int, int, int, long, int, int, int, int)} with a {@code phaseShift} into the cycle. */
    public static double removalProbability(int worldX, int y, int bedY, long startX, int phaseShift, int fade, int voidHold, int endHold, int owHold) {
        return removalProbabilityFromRamp(middleRamp(worldX, startX, phaseShift, fade, voidHold, endHold, owHold), y, bedY);
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
     * band {@code endRamp}: {@link #ISLAND_CORE_DENSITY} in the End core
     * ({@code endRamp == 1}), rising toward {@link #ISLAND_EDGE_DENSITY} as the End
     * fades into the void holds ({@code endRamp → 0}). The core value is 0 — i.e. the
     * exact End surface ({@code density > 0}), since the caller trilinearly
     * interpolates the density like the real generator — and only ramps up at the band
     * edges so the End terrain dissolves smoothly into void. Pure / unit-testable; the
     * density itself comes from the real End noise router.
     */
    public static double islandDensityThreshold(double endRamp) {
        return ISLAND_CORE_DENSITY + (1.0 - clamp01(endRamp)) * (ISLAND_EDGE_DENSITY - ISLAND_CORE_DENSITY);
    }

    /** Density threshold in the End core — 0 = the exact real-End surface. */
    public static final double ISLAND_CORE_DENSITY = 0.0;
    /** Density threshold at the very edge of the End band (endRamp → 0) — only dense cores remain. */
    public static final double ISLAND_EDGE_DENSITY = 0.18;

    /** Deterministic, clumpy erosion noise in {@code [0,1)} (two octaves), seeded from the world seed. */
    public static double coherentNoise(long seed, int x, int y, int z) {
        return coherentNoise(seed, x, y, z, 1);
    }

    /**
     * {@link #coherentNoise(long, int, int, int)} sampled at {@code cellScale}× the base feature size —
     * larger scale ⇒ bigger, further-apart clumps (islands + gaps grow together). {@code cellScale} is
     * clamped to ≥ 1, so {@code 1} reproduces the base noise exactly.
     */
    public static double coherentNoise(long seed, int x, int y, int z, int cellScale) {
        int s = Math.max(1, cellScale);
        double coarse = valueNoise(seed, x, y, z, NOISE_CELL_COARSE * s);
        double fine = valueNoise(seed ^ 0x5DEECE66DL, x, y, z, NOISE_CELL_FINE * s);
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
