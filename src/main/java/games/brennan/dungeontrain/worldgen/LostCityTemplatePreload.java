package games.brennan.dungeontrain.worldgen;

/**
 * When to pre-load Big Lost City's structure templates, so the first city a player reaches doesn't stall
 * worldgen on them, and when to let them go again. A city can place 42 of the mod's 75 templates
 * ({@link LostCityTemplateIds}; ~7.5 MB compressed, ~90 MB raw 1.20.1 NBT that vanilla datafixes on load — the
 * other 33, ~140 MB raw, no pool names). A jigsaw start loads its pieces on first use, which without a pre-load
 * happens right as the train meets the first city — ~0.5 s of datafixing per template on a worldgen thread.
 * {@code event/LostCityTemplatePreloadEvents} loads them on a background thread instead and evicts them once
 * nothing needs them. Measured on a dev server (0.1071.1): the 42 hold ~120 MB of block infos and positions and
 * load in ~23 s on the background thread; all 75 held ~300 MB (~350 MB more heap after GC) for the rest of the
 * session and took ~61 s.
 *
 * <p><b>What holds the cache is where chunks are generated, not where a player stands.</b> Each player (and
 * each absent player's last overworld position — the train they return to) has a {@link Need}, read with the
 * {@link #reachBlocks generation reach} around them: their view generates chunks, and those chunks read
 * structure starts, well ahead of their own X. On top of that, {@link LostCityTemplateDemand} reports every
 * Lost City start actually approved, whoever is generating it; a start with the cache cold starts the pre-load
 * there and then, and eviction waits until starts have stopped for {@link #DEMAND_HOLD_NANOS}. That is what
 * covers Distant Horizons, whose LOD workers generate thousands of blocks beyond any lookahead.</p>
 *
 * <p>The WWOO stretch shows only a per-world pick of the buildings ({@link LostCityStructures#wwooBuildings}),
 * so approaching it pre-loads just that pick; the run pre-loads everything. Eviction needs every player
 * {@link Need#QUIET} — beyond the trigger distance by {@link #EVICT_MARGIN_BLOCKS}, so hovering at the edge
 * loads once — for {@link #EVICT_AFTER_SCANS} seconds in a row.</p>
 */
public final class LostCityTemplatePreload {

    /** How far ahead (and behind — the train can ride either way) a city column of the run triggers the pre-load. */
    public static final int LOOKAHEAD_BLOCKS = 3000;

    /**
     * How much further than a trigger distance a player must be before they count as quiet. Loading starts at
     * the trigger distance and eviction only beyond this band, so a player hovering at the edge — or the train
     * reversing across it — holds the cache instead of evicting and reloading it.
     */
    public static final int EVICT_MARGIN_BLOCKS = 1000;

    /**
     * How far beyond a player's {@link #reachBlocks generation reach} the WWOO stretch triggers the foretaste
     * pre-load: a few templates at ~0.5 s each, against a train covering a few blocks a second.
     */
    public static final int FORETASTE_LOOKAHEAD_BLOCKS = 1024;

    /**
     * Chunks beyond the view distance that a player's view still generates into: a chunk reads the structure
     * starts of chunks up to 8 away, and the ticket levels around the view add a couple more.
     */
    public static final int GEN_MARGIN_CHUNKS = 10;

    /**
     * Sampling step across a window. Every stretch with a non-zero density is far longer than this (the
     * WWOO stretch is thousands of blocks; the Lost City run longer still), so none can hide between samples.
     */
    static final int STEP_BLOCKS = 256;

    /** Consecutive quiet scans (one a second) before the templates are evicted. */
    public static final int EVICT_AFTER_SCANS = 30;

    /**
     * How long after the last approved Lost City start the cache is held whatever the players' positions. A
     * generator working through a run approves starts every few seconds; two minutes without one means it has
     * left or finished.
     */
    public static final long DEMAND_HOLD_NANOS = 120L * 1_000_000_000L;

    /** A player X change in one tick that is a teleport, not travel — the scan runs at once instead of on its second. */
    public static final int JUMP_BLOCKS = 128;

    /** What a position needs of the template cache, weakest first. */
    public enum Need {
        /** Nothing: this position counts toward eviction. */
        QUIET,
        /** Nothing new, but too close to a trigger distance to evict. */
        HOLD,
        /** The WWOO stretch is within generation reach: this world's foretaste pick. */
        FORETASTE,
        /** The Lost City run is within the lookahead: every placeable template. */
        RUN
    }

    /** How positions are read; {@link #MODE} is the A/B seam ({@code /dungeontrain debug lost-city-templates}). */
    public enum Mode {
        /** Generation reach, hysteresis band, demand signal, foretaste pre-load (shipping behaviour). */
        REACH,
        /**
         * The pre-change rule (0.1071–0.1111): the player's own X, one threshold for loading and evicting,
         * the WWOO stretch loading on demand, no demand signal — the baseline for measuring the change.
         */
        LEGACY
    }

    /** Live A/B seam; {@code volatile} so worldgen threads observe a flip immediately. */
    public static volatile Mode MODE = Mode.REACH;

    private LostCityTemplatePreload() {}

    /** Blocks around a player their view can generate into, for a view distance in chunks. */
    public static int reachBlocks(int viewDistanceChunks) {
        return (Math.max(0, viewDistanceChunks) + GEN_MARGIN_CHUNKS) * 16;
    }

    /** What a player at {@code worldX} with generation reach {@code reachBlocks} needs of the cache. */
    public static Need needAt(WorldGenCycle cycle, int worldX, int reachBlocks, Mode mode) {
        if (mode == Mode.LEGACY) {
            if (nearLostCity(cycle, worldX, LOOKAHEAD_BLOCKS)) return Need.RUN;
            return LostCityStructures.inWwooStretch(cycle, worldX >> 4) ? Need.HOLD : Need.QUIET;
        }
        if (nearLostCity(cycle, worldX, LOOKAHEAD_BLOCKS)) return Need.RUN;
        int foretaste = Math.max(0, reachBlocks) + FORETASTE_LOOKAHEAD_BLOCKS;
        if (nearWwooStretch(cycle, worldX, foretaste)) return Need.FORETASTE;
        if (nearLostCity(cycle, worldX, LOOKAHEAD_BLOCKS + EVICT_MARGIN_BLOCKS)
                || nearWwooStretch(cycle, worldX, foretaste + EVICT_MARGIN_BLOCKS)) {
            return Need.HOLD;
        }
        return Need.QUIET;
    }

    /**
     * Whether a player at {@code worldX} needs none of the Lost City's templates and is far enough from needing
     * them to evict: neither the run nor the WWOO stretch is within its trigger distance plus
     * {@link #EVICT_MARGIN_BLOCKS}, the stretch's distance counted from the edge of the player's generation reach.
     */
    public static boolean quietAt(WorldGenCycle cycle, int worldX, int reachBlocks) {
        return needAt(cycle, worldX, reachBlocks, Mode.REACH) == Need.QUIET;
    }

    /** The quiet-scan count after a scan: one more if the scan was quiet, else back to 0. */
    public static int nextQuietScans(int quietScans, boolean allQuiet) {
        return allQuiet ? quietScans + 1 : 0;
    }

    /** Whether a scan counts toward eviction: every position quiet, no pre-load running, no recent demand. */
    public static boolean quietScan(Need strongest, boolean preloadRunning, boolean demandHeld) {
        return strongest == Need.QUIET && !preloadRunning && !demandHeld;
    }

    /** Whether an approved start at {@code lastNanos} still holds the cache at {@code nowNanos}; never if none was {@code seen}. */
    public static boolean demandHolds(boolean seen, long nowNanos, long lastNanos) {
        return seen && nowNanos - lastNanos < DEMAND_HOLD_NANOS;
    }

    /** Whether a move from {@code lastX} to {@code x} in one tick is a teleport. */
    public static boolean jumped(int lastX, int x) {
        return Math.abs((long) x - lastX) >= JUMP_BLOCKS;
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

    /** Whether a WWOO stretch (the foretaste) has a chunk column within {@code window} blocks of {@code worldX}. */
    public static boolean nearWwooStretch(WorldGenCycle cycle, int worldX, int window) {
        if (cycle == null || !cycle.hasLayout()) return false;
        long from = (long) worldX - window;
        long to = (long) worldX + window;
        for (long x = from; x <= to; x += STEP_BLOCKS) {
            if (LostCityStructures.inWwooStretch(cycle, chunkOf(x))) return true;
        }
        return LostCityStructures.inWwooStretch(cycle, chunkOf(to));
    }

    private static boolean runCityAt(WorldGenCycle cycle, long worldX) {
        int chunkX = chunkOf(worldX);
        return !LostCityStructures.inWwooStretch(cycle, chunkX) && LostCityStructures.density(cycle, chunkX) > 0.0D;
    }

    private static int chunkOf(long worldX) {
        return ((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, worldX))) >> 4;
    }
}
