package games.brennan.dungeontrain.worldgen;

/**
 * When to pre-load Big Lost City's structure templates, so the first city a player reaches doesn't stall
 * worldgen on them. The mod ships 75 templates (~19 MB, 1.20.1 NBT that vanilla datafixes on load); a
 * jigsaw start loads its pieces on first use, which without a pre-load happens right as the train meets the
 * first city. {@code event/LostCityTemplatePreloadEvents} loads them on a background thread once a player
 * comes within {@link #LOOKAHEAD_BLOCKS} of the Lost City run.
 *
 * <p>Only the run counts, not the few ruins sprinkled through the WWOO stretch: loading all 75 templates
 * holds ~350–400 MB for the rest of the session, which the run (it shows nearly every building) repays and
 * a handful of foretaste buildings — a per-world half of them at most ({@link LostCityStructures#wwooBuildings})
 * — do not; those load on demand on worldgen threads.</p>
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

    /** Whether the Lost City run (not the WWOO foretaste) can start a city within {@code lookahead} blocks of {@code worldX}. */
    public static boolean nearLostCity(WorldGenCycle cycle, int worldX, int lookahead) {
        if (cycle == null || !cycle.hasLayout()) return false;
        long from = (long) worldX - lookahead;
        long to = (long) worldX + lookahead;
        for (long x = from; x <= to; x += STEP_BLOCKS) {
            if (runCityAt(cycle, x)) return true;
        }
        return runCityAt(cycle, to);
    }

    private static boolean runCityAt(WorldGenCycle cycle, long worldX) {
        int chunkX = ((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, worldX))) >> 4;
        return !LostCityStructures.inWwooStretch(cycle, chunkX) && LostCityStructures.density(cycle, chunkX) > 0.0D;
    }
}
