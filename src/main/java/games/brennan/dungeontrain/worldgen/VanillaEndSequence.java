package games.brennan.dungeontrain.worldgen;

/**
 * The order a <b>vanilla</b> End-band piece shows its biomes in: small End islands first, then End
 * highlands. Vanilla's own End hands a traveller the main island and then whatever the outer noise
 * field holds; the band instead walks the piece from its sparse edge to its heartland, so the first End
 * crossing reads as "mini islands, then the highlands" (chorus and End cities belong to the second part,
 * as in vanilla).
 *
 * <p>Position is the fraction {@code progress} through the piece's core ({@code WorldGenCycle#endPieceProgress}).
 * The switch is not a wall: around {@link #SMALL_ISLANDS_SHARE} the highlands' share rises {@code 0 → 1}
 * over {@code ±}{@link #SEAM_HALF_BLEND}, and a seed-stable column noise picks each column's stage, so
 * the two interleave in clumps the way the joined-band seam does ({@code WorldGenCycle#endSourcePassAt}).</p>
 *
 * <p>Pure logic — no Minecraft state — so it is unit-tested directly.</p>
 */
public final class VanillaEndSequence {

    /** The two stages of a vanilla End piece, in order. */
    public enum Stage { SMALL_ISLANDS, HIGHLANDS }

    /** Fraction of the piece's core that is small islands before the highlands begin. */
    public static final double SMALL_ISLANDS_SHARE = 1.0 / 3.0;

    /** Half-width of the crossfade between the stages, as a fraction of the core (≈100 blocks of 1200). */
    public static final double SEAM_HALF_BLEND = 1.0 / 12.0;

    /**
     * Island-fill ramp a small-islands column stamps with ({@code Disintegration#islandDensityThreshold}):
     * well below the core's 1.0, so only the dense hearts of the real End's islands survive — mini islands.
     */
    public static final double SMALL_ISLAND_FILL = 0.35;

    /** Coherent-noise cell scale for the stage crossfade: clumps of whole island columns. */
    public static final int SEAM_NOISE_SCALE = 3;

    /** Salt so the stage dither is independent of the other coherent-noise users. */
    private static final long SEAM_SALT = 0x5EA1_E0D5L;

    private VanillaEndSequence() {}

    /** True when {@code progress} describes a column inside a vanilla End piece (not {@code -1}). */
    public static boolean applies(double progress) {
        return progress >= 0.0;
    }

    /** The highlands' share of columns at {@code progress}: 0 before the seam, 1 after it, linear across it. */
    public static double highlandsShare(double progress) {
        double from = SMALL_ISLANDS_SHARE - SEAM_HALF_BLEND;
        double to = SMALL_ISLANDS_SHARE + SEAM_HALF_BLEND;
        if (progress <= from) return 0.0;
        if (progress >= to) return 1.0;
        return (progress - from) / (to - from);
    }

    /** The stage of a column at {@code progress} whose seam noise is {@code noise01} (in {@code [0, 1)}). */
    public static Stage stageAt(double progress, double noise01) {
        return noise01 < highlandsShare(progress) ? Stage.HIGHLANDS : Stage.SMALL_ISLANDS;
    }

    /** {@link #stageAt(double, double)} with the column noise for {@code (worldX, worldZ)} under {@code seed}. */
    public static Stage stageAt(double progress, long seed, int worldX, int worldZ) {
        double share = highlandsShare(progress);
        if (share <= 0.0) return Stage.SMALL_ISLANDS;   // no noise needed away from the seam
        if (share >= 1.0) return Stage.HIGHLANDS;
        return stageAt(progress, Disintegration.coherentNoise(seed ^ SEAM_SALT, worldX, 0, worldZ, SEAM_NOISE_SCALE));
    }

    /** Island-fill ramp the vanilla stamp uses for a column in {@code stage}. */
    public static double islandFill(Stage stage) {
        return stage == Stage.SMALL_ISLANDS ? SMALL_ISLAND_FILL : 1.0;
    }
}
