package games.brennan.dungeontrain.spawn;

/**
 * The late-run phantom curve: how likely a {@code LatePhantomSpawner} check is to spawn a group, and how big
 * the group is, from how far the player is into the second world-gen cycle. Pure (params in) so it unit-tests
 * without a level.
 *
 * <p>Keyed to <b>blocks past the start</b>, not difficulty level: a run's level is carriages travelled, the
 * cycle is a fixed world-X, so different runs reach it at different levels. One step is
 * {@link #BLOCKS_PER_STEP} blocks, about one level at a typical pace.</p>
 */
public final class LatePhantomRate {

    /** Blocks past the start per curve step (~one difficulty level at a typical pace). */
    public static final int BLOCKS_PER_STEP = 200;
    /** Night chance per check right at the start. */
    public static final double BASE_CHANCE = 0.15;
    /** Night chance added per step. */
    public static final double CHANCE_PER_STEP = 0.005;
    /** Highest night chance per check, reached 150 steps (30,000 blocks) past the start. */
    public static final double MAX_CHANCE = 0.90;
    /** Daytime spawns run at this fraction of the night chance (and wear Fire Resistance). */
    public static final double DAY_FACTOR = 0.25;
    /** Steps per extra phantom in a group. */
    public static final int STEPS_PER_EXTRA_PHANTOM = 40;
    /** Largest group. */
    public static final int MAX_GROUP = 4;
    /**
     * End or Nether sky intensity at or above which the player counts as under that sky box. Vanilla never
     * spawns phantoms in the End or the Nether, so neither do we.
     */
    public static final double FOREIGN_SKY_THRESHOLD = 0.5;

    private LatePhantomRate() {}

    /** Curve steps at {@code worldX}, or {@code -1} at or before the start (no phantoms there). */
    public static long steps(double worldX, long startX) {
        if (worldX <= startX) return -1L;
        return (long) Math.floor((worldX - startX) / BLOCKS_PER_STEP);
    }

    /** Chance one check spawns a group, {@code steps >= 0}. Night at full rate, day at {@link #DAY_FACTOR}. */
    public static double chance(long steps, boolean night) {
        if (steps < 0L) return 0.0;
        double nightChance = Math.min(MAX_CHANCE, BASE_CHANCE + CHANCE_PER_STEP * steps);
        return night ? nightChance : nightChance * DAY_FACTOR;
    }

    /** Phantoms in a group, {@code steps >= 0}. */
    public static int groupSize(long steps) {
        if (steps < 0L) return 0;
        return (int) Math.min(MAX_GROUP, 1L + steps / STEPS_PER_EXTRA_PHANTOM);
    }

    /** Steps at which the night chance reaches {@link #MAX_CHANCE} (150 with the defaults). */
    public static final long STEPS_TO_MAX = Math.round((MAX_CHANCE - BASE_CHANCE) / CHANCE_PER_STEP);

    /**
     * Mob-effect level for a phantom at {@code steps}: 0 until the spawn rate tops out, then one level per
     * step (a step is ~one difficulty level), fed to the same effect curve every other mob uses.
     */
    public static int effectLevel(long steps) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, steps - STEPS_TO_MAX));
    }

    /** True when the column's sky is the overworld's: neither the End/void nor the Nether sky box. */
    public static boolean overworldSky(double endSky, double netherSky) {
        return endSky < FOREIGN_SKY_THRESHOLD && netherSky < FOREIGN_SKY_THRESHOLD;
    }
}
