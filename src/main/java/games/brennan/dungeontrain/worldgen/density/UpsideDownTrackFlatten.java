package games.brennan.dungeontrain.worldgen.density;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;

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
 * (away from the track), so mountains slope down into foothills rather than being cut off.</p>
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
    /** Blocks either side of the track centre held at full weight. */
    public static final int TRACK_INNER = 80;
    /** Blocks from the track centre where the weight has faded to zero — mountains beyond are untouched. */
    public static final int TRACK_OUTER = 288;
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
     */
    public record Context(boolean enabled, WorldGenCycle cycle, int trackCenterZ) {}

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

    /** Z weight {@code 0..1}: 1 within {@link #TRACK_INNER} of the track centre, smoothly 0 by {@link #TRACK_OUTER}. */
    public static double trackWeight(int worldZ, int trackCenterZ) {
        int d = Math.abs(worldZ - trackCenterZ);
        if (d <= TRACK_INNER) return 1.0;
        if (d >= TRACK_OUTER) return 0.0;
        return smoothstep(1.0 - (double) (d - TRACK_INNER) / (TRACK_OUTER - TRACK_INNER));
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
        double wz = trackWeight(worldZ, ctx.trackCenterZ());
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
