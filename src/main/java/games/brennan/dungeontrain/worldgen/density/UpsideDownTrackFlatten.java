package games.brennan.dungeontrain.worldgen.density;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.feature.MountainNoise;

/**
 * Keeps mountains off the track in the upside-down band. The band is a vertical mirror of ordinary
 * overworld terrain about the train plane ({@code UpsideDownMirror}), so any source terrain that rises
 * above the train reflects into a solid hang beneath it — the train ends up tunnelling through a
 * mountain. Terrain beside the track is fine; terrain across it is not.
 *
 * <p>Terrain height in 1.21 is not decided by the biome: terrain and biome are both read from the same
 * climate noises. So instead of relabelling biomes (which would leave the mountain and paint it wrong),
 * this weights the <b>erosion</b> climate input up toward a lowland floor near the track. High erosion
 * is flat terrain <em>and</em> lowland biomes, so the two stay coherent, and mountains further out are
 * left as they are. The weight blends smoothly in both X (into and out of the band's stretch) and Z
 * (away from the track), so mountains slope down into foothills rather than being cut off. Each side
 * of the track has its own <b>noisy edge</b> that wanders along X — in places the mountains come right
 * up to within a chunk of the line, elsewhere the lowland opens out wider.</p>
 *
 * <p>This class is the pure maths (unit-tested without a NeoForge bootstrap, like
 * {@link BandBiomeDecision}); {@link TrackErosionDensityFunction} applies it inside the noise router,
 * reading the per-world {@link Context} lazily.</p>
 */
public final class UpsideDownTrackFlatten {

    /**
     * Erosion floor near the track. Vanilla's erosion band 4 ({@code 0.05..0.45}) is the flat lowland
     * band of the overworld offset spline — plains/forest ground that sits below the train plane.
     */
    public static final double EROSION_FLOOR = 0.25;
    /** Full-weight half-width where the noisy edge pulls in tightest — just clear of the corridor. */
    public static final int TRACK_INNER_MIN = 6;
    /** Full-weight half-width where the noisy edge opens out widest. */
    public static final int TRACK_INNER_MAX = 32;
    /** Fade-out distance at the tightest edge — mountains may stand one chunk from the track. */
    public static final int TRACK_OUTER_MIN = 16;
    /** Fade-out distance at the widest edge — mountains beyond are untouched everywhere. */
    public static final int TRACK_OUTER_MAX = 160;
    /**
     * Scales world-X into the edge noise: with {@link MountainNoise#smooth01}'s 96-block base octave
     * this gives edge swings roughly every couple of hundred blocks.
     */
    private static final double EDGE_NOISE_SCALE = 0.5;
    /** Salts decorrelating the two sides' edges from each other and from the mountain noise. */
    private static final long EDGE_SALT_POS = 0x5DEECE66DL * 31L + 0x2545F4914F6CDD1DL;
    private static final long EDGE_SALT_NEG = 0x9E3779B97F4A7C15L ^ 0x2545F4914F6CDD1DL;
    /** Blocks outside each end of the upside-down stretch over which the weight ramps in / out. */
    public static final int BAND_RAMP = 160;
    /** Coarse probe step of the distance search in {@link #bandWeight}; refined to one block after a hit. */
    private static final int PROBE_STEP = 8;

    /**
     * Per-world inputs, published at server start (see {@code NetherBandContextEvents}) and read lazily
     * — the router is built before the world data is readable, same as {@link NetherBandContext}.
     *
     * @param enabled      upside-down band active for this world and the flatten switch on
     * @param cycle        the frozen world-gen cycle layout
     * @param trackCenterZ world-Z of the track centre line
     * @param seed         per-world generation seed driving the noisy edge
     */
    public record Context(boolean enabled, WorldGenCycle cycle, int trackCenterZ, long seed) {}

    private static volatile Context current;

    private UpsideDownTrackFlatten() {}

    /** The active context, or {@code null} before {@link #publish} / after {@link #clear}. */
    public static Context current() {
        return current;
    }

    public static void publish(Context context) {
        current = context;
    }

    public static void clear() {
        current = null;
    }

    /** {@code 3t² − 2t³} on {@code t} clamped to {@code [0,1]}. */
    static double smoothstep(double t) {
        double c = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
        return c * c * (3.0 - 2.0 * c);
    }

    /**
     * How far out this side's edge sits at {@code worldX}: {@code 0} = pulled in tightest
     * ({@link #TRACK_INNER_MIN}/{@link #TRACK_OUTER_MIN}), {@code 1} = widest
     * ({@link #TRACK_INNER_MAX}/{@link #TRACK_OUTER_MAX}). Smooth along X; the two sides of the track
     * ({@code positiveSide}) wander independently. Fractal noise clusters around the middle, so it is
     * stretched to reach both extremes.
     */
    public static double edgeOpenness(long seed, int worldX, boolean positiveSide) {
        long salt = positiveSide ? EDGE_SALT_POS : EDGE_SALT_NEG;
        double n = MountainNoise.smooth01(seed ^ salt, worldX * EDGE_NOISE_SCALE, 0.0);
        return smoothstep((n - 0.3) / 0.4);
    }

    /**
     * Z weight {@code 0..1} for an edge of the given {@code openness}: 1 within the full-weight half-width,
     * smoothly 0 by the fade-out distance, both interpolated between their tight and wide extremes.
     */
    public static double trackWeight(int worldZ, int trackCenterZ, double openness) {
        int d = Math.abs(worldZ - trackCenterZ);
        double o = openness < 0.0 ? 0.0 : (openness > 1.0 ? 1.0 : openness);
        double inner = TRACK_INNER_MIN + o * (TRACK_INNER_MAX - TRACK_INNER_MIN);
        double outer = TRACK_OUTER_MIN + o * (TRACK_OUTER_MAX - TRACK_OUTER_MIN);
        if (d <= inner) return 1.0;
        if (d >= outer) return 0.0;
        return smoothstep(1.0 - (d - inner) / (outer - inner));
    }

    /**
     * X weight {@code 0..1}: 1 anywhere the upside-down mirror touches ({@link WorldGenCycle#isInUpsideDownStretch}),
     * ramping smoothly to 0 over {@link #BAND_RAMP} blocks outside each end; 0 everywhere else. An O(1)
     * {@link WorldGenCycle#upsideDownInfluence} check answers the far-away majority before any search.
     */
    public static double bandWeight(WorldGenCycle cycle, int worldX) {
        if (cycle == null || !cycle.upsideDownInfluence(worldX, BAND_RAMP)) return 0.0;
        int d = distanceToStretch(cycle, worldX, BAND_RAMP);
        if (d < 0) return 0.0;
        if (d == 0) return 1.0;
        return smoothstep(1.0 - (double) d / BAND_RAMP);
    }

    /**
     * Blocks from {@code worldX} to the nearest upside-down stretch column (0 inside it), or {@code -1}
     * when none lies within {@code max}. Probes both sides every {@link #PROBE_STEP} blocks, then refines
     * the first hit to the exact block.
     */
    static int distanceToStretch(WorldGenCycle cycle, int worldX, int max) {
        if (cycle.isInUpsideDownStretch(worldX)) return 0;
        for (int d = PROBE_STEP; d < max + PROBE_STEP; d += PROBE_STEP) {
            int probe = Math.min(d, max);
            if (hitAt(cycle, worldX, probe)) {
                for (int e = probe - PROBE_STEP + 1; e < probe; e++) {
                    if (e > 0 && hitAt(cycle, worldX, e)) return e;
                }
                return probe;
            }
        }
        return -1;
    }

    private static boolean hitAt(WorldGenCycle cycle, int worldX, int d) {
        return cycle.isInUpsideDownStretch(worldX - d) || cycle.isInUpsideDownStretch(worldX + d);
    }

    /** Combined weight at a column; 0 when the context is missing or disabled. */
    public static double weight(Context ctx, int worldX, int worldZ) {
        if (ctx == null || !ctx.enabled()) return 0.0;
        if (Math.abs(worldZ - ctx.trackCenterZ()) >= TRACK_OUTER_MAX) return 0.0;
        double wz = trackWeight(worldZ, ctx.trackCenterZ(),
                edgeOpenness(ctx.seed(), worldX, worldZ >= ctx.trackCenterZ()));
        if (wz <= 0.0) return 0.0;
        double wx = bandWeight(ctx.cycle(), worldX);
        return wx * wz;
    }

    /**
     * Erosion nudged toward {@link #EROSION_FLOOR} by weight {@code w}: {@code e + w·max(0, floor − e)}.
     * Never lowers erosion, so ground that is already flat is left exactly as it was; {@code w ≤ 0} is a
     * pure pass-through.
     */
    public static double apply(double erosion, double w) {
        if (w <= 0.0 || erosion >= EROSION_FLOOR) return erosion;
        return erosion + Math.min(1.0, w) * (EROSION_FLOOR - erosion);
    }
}
