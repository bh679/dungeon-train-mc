package games.brennan.dungeontrain.worldgen;

/**
 * When to pre-load Big Lost City's structure templates, so the first city a player reaches doesn't stall
 * worldgen on them, and when to let them go again. A city can place 42 of the mod's 75 templates
 * ({@link LostCityTemplateIds}; ~7.5 MB compressed, ~90 MB raw 1.20.1 NBT that vanilla datafixes on load — the
 * other 33, ~140 MB raw, no pool names). A jigsaw start loads its pieces on first use, which without a pre-load
 * happens right as the train meets the first city. {@code event/LostCityTemplatePreloadEvents} loads them on a
 * background thread once a player comes within {@link #LOOKAHEAD_BLOCKS} of the Lost City run, and evicts them
 * once every player has been {@link #quietAt quiet} for {@link #EVICT_AFTER_SCANS} seconds.
 *
 * <p>Only the run counts, not the few ruins sprinkled through the WWOO stretch: the cache holds a loaded
 * template until it is evicted, which the run (it shows nearly every building) repays and a handful of
 * foretaste buildings — a per-world half of them at most ({@link LostCityStructures#wwooBuildings}) — do not;
 * those load on demand on worldgen threads, and the stretch holds off eviction so they aren't reloaded mid-stretch.</p>
 */
public final class LostCityTemplatePreload {

    /** How far ahead (and behind — the train can ride either way) a city column triggers the pre-load. */
    public static final int LOOKAHEAD_BLOCKS = 3000;

    /**
     * Sampling step across the window. Every stretch with a non-zero density is far longer than this (the
     * WWOO stretch is thousands of blocks; the Lost City run longer still), so none can hide between samples.
     */
    static final int STEP_BLOCKS = 256;

    /**
     * Consecutive quiet scans (one a second) before the templates are evicted — hysteresis so a player hovering
     * at the window's edge, or the train reversing, doesn't evict and reload them back to back.
     */
    public static final int EVICT_AFTER_SCANS = 30;

    private LostCityTemplatePreload() {}

    /**
     * Whether a player at {@code worldX} needs none of the Lost City's templates: not within {@link #LOOKAHEAD_BLOCKS}
     * of the run, and not in the WWOO stretch, whose foretaste buildings load on demand.
     */
    public static boolean quietAt(WorldGenCycle cycle, int worldX) {
        return !nearLostCity(cycle, worldX, LOOKAHEAD_BLOCKS) && !LostCityStructures.inWwooStretch(cycle, worldX >> 4);
    }

    /** The quiet-scan count after a scan: one more if every player was {@link #quietAt quiet}, else back to 0. */
    public static int nextQuietScans(int quietScans, boolean allQuiet) {
        return allQuiet ? quietScans + 1 : 0;
    }

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
