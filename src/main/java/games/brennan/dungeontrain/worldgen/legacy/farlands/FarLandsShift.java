package games.brennan.dungeontrain.worldgen.legacy.farlands;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;

/**
 * Where a Far Lands band chunk reads Beta's terrain from: the band is the ordinary Beta generator
 * ({@link games.brennan.dungeontrain.worldgen.legacy.beta.BetaTerrain}) sampled ~12.55 million blocks out,
 * with the world chunk translated by {@code (dxChunks, dzChunks)}.
 *
 * <p><b>Why that far.</b> Beta's two 16-octave limit noises sample at {@code 684.412} noise units per
 * 4-block cell (171.103 per block). Beta floors each coordinate with {@code (int) d}, which saturates at the
 * int range once {@code |171.103 × x|} passes 2³¹ — at ±{@value #EDGE} blocks. From there the top octave's
 * lattice cell stops changing while the in-cell fraction grows without bound, so its interpolation explodes:
 * the Far Lands. Our port keeps both halves of that bug ({@code LegacyMath.floor} is the same saturating
 * cast, and the octave stack has no Beta 1.8 {@code % 16777216} wrap), so no noise change is needed — only
 * the coordinates. The negative edge works the same way (the saturated floor even wraps, as Beta's did).</p>
 *
 * <p><b>The ride</b>, in script blocks from the core start ({@value #SCRIPT_LEN} in all, scaled onto the
 * configured core; the train rides +X, so its left is −Z). A side wall "at d" stands {@code d} blocks from
 * the track ({@code z = 0}) with the Far Lands beyond it, its face towards the train:</p>
 * <ol>
 *   <li>{@code 0–1000} {@link Stage#ENTRY} — {@value #APPROACH} blocks of ordinary Beta land, then the X
 *       edge wall crosses the track and the train rides the edge lands. The entry fade reads this too.</li>
 *   <li>{@code 1000–3000} {@link Stage#CLOSING} — left wall at {@value #SIDE_Z}; the right wall starts
 *       1000 out and steps in every 500 ({@link #CLOSING_STEPS}: long steps first, short last).</li>
 *   <li>{@code 3000–6000} {@link Stage#CANYON} — both walls at {@value #SIDE_Z}.</li>
 *   <li>{@code 6000–8000} {@link Stage#OPENING} — the left wall steps back out ({@link #OPENING_STEPS});
 *       the right one stays.</li>
 *   <li>{@code 8000–9000} {@link Stage#EXIT} — the right wall sweeps across the track
 *       ({@link #SWEEP_STEPS}), the edge lands close over the train, and {@value #APPROACH} blocks before the
 *       end the train breaks out through the negative X edge's wall onto ordinary land, mirroring the entry.
 *       The exit fade reads this too.</li>
 * </ol>
 *
 * <p>Shifts are whole chunks, constant within a step of one band instance and one side of the track
 * (chunks with {@code z < 0} are the left side), so steps and sides meet as the band's chunk walls.</p>
 *
 * @param dxChunks source chunk X minus world chunk X
 * @param dzChunks source chunk Z minus world chunk Z
 */
public record FarLandsShift(int dxChunks, int dzChunks) {

    /** First block where Beta's top limit-noise octave overflows ({@code 2³¹ / 171.103}). */
    public static final int EDGE = 12_550_824;
    /** Ordinary Beta land before the entry wall, and after the exit wall (script blocks). */
    public static final int APPROACH = 256;
    /** Resting distance of a side wall from the track. */
    public static final int SIDE_Z = 56;
    /** Script length; the configured core is scaled onto it. */
    public static final int SCRIPT_LEN = 9000;

    /** Script block where each stage ends. */
    static final int ENTRY_END = 1000;
    static final int CLOSING_END = 3000;
    static final int CANYON_END = 6000;
    static final int OPENING_END = 8000;
    /** Script block where the sweep ends and the whole width is edge lands. */
    static final int SWEEP_END = 8400;
    /** Script block of the exit wall. */
    static final int EXIT_WALL = SCRIPT_LEN - APPROACH;

    /** Moving-wall step length (script blocks). */
    static final int STEP = 500;
    /** Right wall while closing in: eased 1000 → {@value #SIDE_Z}, big steps first. */
    public static final int[] CLOSING_STEPS = {1000, 587, 292, 115};
    /** Left wall while opening out: the closing steps in reverse, small steps first. */
    public static final int[] OPENING_STEPS = {115, 292, 587, 1000};
    /** Right wall sweeping over the track, one step per third of the sweep. */
    public static final int[] SWEEP_STEPS = {20, -20, -60};

    public static final FarLandsShift NONE = new FarLandsShift(0, 0);

    /** The band's stages, in riding order. */
    public enum Stage { ENTRY, CLOSING, CANYON, OPENING, EXIT }

    /** Source block X offset. */
    public int dxBlocks() {
        return dxChunks << 4;
    }

    /** Source block Z offset. */
    public int dzBlocks() {
        return dzChunks << 4;
    }

    /** The shift for world chunk {@code (chunkX, chunkZ)} under the live cycle; {@link #NONE} outside the band's slot. */
    public static FarLandsShift of(WorldGenCycle cycle, int chunkX, int chunkZ) {
        long coreStart = cycle.legacyCoreStartX(LegacyBandKind.FAR_LANDS, chunkX << 4);
        if (coreStart == WorldGenCycle.NOT_IN_LEGACY_SLOT) return NONE;
        return forChunk(coreStart, cycle.legacyLen(LegacyBandKind.FAR_LANDS), chunkX, chunkZ);
    }

    /** Script position of world chunk {@code chunkX}'s west edge (negative in the entry fade). */
    static double scriptPos(long coreStartX, long holdLen, int chunkX) {
        long local = ((long) chunkX << 4) - coreStartX;
        return holdLen <= 0L ? local : (double) local * SCRIPT_LEN / holdLen;
    }

    /** The stage at world chunk {@code chunkX} of a band whose core starts at {@code coreStartX}. */
    public static Stage stageAt(long coreStartX, long holdLen, int chunkX) {
        double p = scriptPos(coreStartX, holdLen, chunkX);
        if (p < ENTRY_END) return Stage.ENTRY;
        if (p < CLOSING_END) return Stage.CLOSING;
        if (p < CANYON_END) return Stage.CANYON;
        if (p < OPENING_END) return Stage.OPENING;
        return Stage.EXIT;
    }

    /** Pure form of {@link #of}. */
    public static FarLandsShift forChunk(long coreStartX, long holdLen, int chunkX, int chunkZ) {
        double p = scriptPos(coreStartX, holdLen, chunkX);
        boolean left = chunkZ < 0;
        return switch (stageAt(coreStartX, holdLen, chunkX)) {
            case ENTRY -> xEdge(EDGE - worldX(coreStartX, holdLen, APPROACH));
            case CLOSING -> left ? leftWall(SIDE_Z) : rightWall(step(CLOSING_STEPS, p - ENTRY_END, STEP));
            case CANYON -> left ? leftWall(SIDE_Z) : rightWall(SIDE_Z);
            case OPENING -> left ? leftWall(step(OPENING_STEPS, p - CANYON_END, STEP)) : rightWall(SIDE_Z);
            case EXIT -> p < SWEEP_END
                    ? rightWall(step(SWEEP_STEPS, p - OPENING_END,
                            (double) (SWEEP_END - OPENING_END) / SWEEP_STEPS.length))
                    // Negative X edge: overflowed until the exit wall, ordinary land after it.
                    : xEdge(-(long) EDGE - worldX(coreStartX, holdLen, EXIT_WALL));
        };
    }

    /** World X of script block {@code script}. */
    private static long worldX(long coreStartX, long holdLen, int script) {
        return coreStartX + (holdLen <= 0L ? script : Math.round((double) script * holdLen / SCRIPT_LEN));
    }

    private static int step(int[] steps, double into, double stepLen) {
        int i = (int) Math.floor(into / stepLen);
        return steps[Math.max(0, Math.min(steps.length - 1, i))];
    }

    /** Whole-width X-edge lands, sampled at {@code worldX + dxBlocks}. */
    private static FarLandsShift xEdge(long dxBlocks) {
        return new FarLandsShift((int) Math.floorDiv(dxBlocks, 16L), 0);
    }

    /** Far Lands from {@code z ≥ d} (the train's right); {@code d} may be negative to cover the track. */
    static FarLandsShift rightWall(int d) {
        return new FarLandsShift(0, Math.floorDiv(EDGE - d, 16));
    }

    /** Far Lands from {@code z ≤ −d} (the train's left); the mirror image of {@link #rightWall}. */
    static FarLandsShift leftWall(int d) {
        return new FarLandsShift(0, -Math.floorDiv(EDGE - d, 16));
    }
}
