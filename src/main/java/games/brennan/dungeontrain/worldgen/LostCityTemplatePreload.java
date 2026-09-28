package games.brennan.dungeontrain.worldgen;

/**
 * When to pre-load Big Lost City's structure templates, so the first city a player reaches doesn't stall
 * worldgen on them. The mod ships 75 templates (~19 MB, 1.20.1 NBT that vanilla datafixes on load); a
 * jigsaw start loads its pieces on first use, which without a pre-load happens right as the train meets the
 * first city. {@code event/LostCityTemplatePreloadEvents} loads them on a background thread once a player
 * comes within {@link #LOOKAHEAD_BLOCKS} of any column where a city can start.
 *
 * <p>Pure logic over {@link LostCityStructures#density}, which already answers "can a city start here" for
 * both the Lost City run and the few ruins sprinkled through Lap 1's WWOO stretch.</p>
 */
public final class LostCityTemplatePreload {

    /** How far ahead (and behind — the train can ride either way) a city column triggers the pre-load. */
    public static final int LOOKAHEAD_BLOCKS = 3000;

    /**
     * Sampling step across the window. Every stretch with a non-zero density is far longer than this (the
     * WWOO stretch is thousands of blocks; the Lost City run longer still), so none can hide between samples.
     */
    static final int STEP_BLOCKS = 256;

    private LostCityTemplatePreload() {}

    /** Whether a Lost City start is possible anywhere within {@code lookahead} blocks of {@code worldX}. */
    public static boolean nearLostCity(WorldGenCycle cycle, int worldX, int lookahead) {
        if (cycle == null || !cycle.hasLayout()) return false;
        long from = (long) worldX - lookahead;
        long to = (long) worldX + lookahead;
        for (long x = from; x <= to; x += STEP_BLOCKS) {
            if (densityAt(cycle, x) > 0.0D) return true;
        }
        return densityAt(cycle, to) > 0.0D;
    }

    private static double densityAt(WorldGenCycle cycle, long worldX) {
        long clamped = Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, worldX));
        return LostCityStructures.density(cycle, ((int) clamped) >> 4);
    }
}
