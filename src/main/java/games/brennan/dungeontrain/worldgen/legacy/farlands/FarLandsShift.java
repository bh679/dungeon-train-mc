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
 * the track ({@code z = 0}) with the Far Lands beyond it, its face towards the train. Stretches where the
 * train rides <i>inside</i> the Far Lands are kept short; the side-wall stages carry the ride:</p>
 * <ol>
 *   <li>{@link Stage#ENTRY} — {@value #APPROACH} blocks of ordinary Beta land, then the X edge wall crosses
 *       the track and the train rides the edge lands for {@value #ENTRY_INSIDE}. The entry fade reads this.</li>
 *   <li>{@link Stage#CLOSING} ({@value #STAGE_LEN}) — left wall at {@value #SIDE_Z}; the right wall starts
 *       {@value #FAR_Z} out and closes in chunk by chunk on a power-{@value #EASE_POWER} ease
 *       ({@link #closingDistance}): fast at first, creeping at the end.</li>
 *   <li>{@link Stage#CANYON} ({@value #STAGE_LEN}) — both walls at {@value #SIDE_Z}.</li>
 *   <li>{@link Stage#OPENING} ({@value #STAGE_LEN}) — the left wall eases back out, the mirror image
 *       ({@link #openingDistance}); the right one stays.</li>
 *   <li>{@link Stage#EXIT} — the right wall steps beside the track for {@value #SWEEP_BESIDE}, then sweeps
 *       over it ({@link #SWEEP_INSIDE_STEPS}), the edge lands close over the train for {@value #EXIT_INSIDE},
 *       and it breaks out through the negative X edge's wall onto {@value #APPROACH} blocks of ordinary land,
 *       mirroring the entry. The exit fade reads this too.</li>
 * </ol>
 *
 * <p>Shifts are whole chunks, fixed per chunk column of one band instance and side of the track (chunks
 * with {@code z < 0} are the left side), so a moving wall snaps to 16 blocks and its face steps chunk by
 * chunk — the band's chunk-wall look.</p>
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
    /** Riding inside the edge lands after the entry wall, before the side walls open up. */
    static final int ENTRY_INSIDE = 368;
    /** Length of each side-wall stage: closing, canyon, opening. */
    static final int STAGE_LEN = 1000;
    /** Exit sweep: the right wall beside the track, before it crosses. */
    static final int SWEEP_BESIDE = 136;
    /** Exit sweep: each step of the right wall once it is over the track. */
    static final int SWEEP_INSIDE_STEP = 64;
    /** Whole-width edge lands over the train, after the sweep and before the exit wall. */
    static final int EXIT_INSIDE = 176;

    /** Script block where each stage ends. */
    static final int ENTRY_END = APPROACH + ENTRY_INSIDE;
    static final int CLOSING_END = ENTRY_END + STAGE_LEN;
    static final int CANYON_END = CLOSING_END + STAGE_LEN;
    static final int OPENING_END = CANYON_END + STAGE_LEN;
    /** Script block where the sweeping right wall reaches the track. */
    static final int SWEEP_BESIDE_END = OPENING_END + SWEEP_BESIDE;
    /** Script block where the sweep ends and the whole width is edge lands. */
    static final int SWEEP_END = SWEEP_BESIDE_END + 2 * SWEEP_INSIDE_STEP;
    /** Script block of the exit wall. */
    static final int EXIT_WALL = SWEEP_END + EXIT_INSIDE;
    /** Script length; the configured core is scaled onto it. */
    public static final int SCRIPT_LEN = EXIT_WALL + APPROACH;

    /** Where a moving side wall is at its farthest. */
    static final int FAR_Z = 1000;
    /**
     * Power of the moving-wall ease. Closing, the wall stands at {@code SIDE_Z + (FAR_Z − SIDE_Z)·(1 − t)^p}:
     * it rushes in at first and creeps the last stretch. 3 is a gentler (cubed) curve.
     */
    public static final int EASE_POWER = 4;
    /** Right wall beside the track as the exit sweep begins. */
    public static final int SWEEP_BESIDE_Z = 20;
    /** Right wall over the track, one per {@link #SWEEP_INSIDE_STEP}. */
    public static final int[] SWEEP_INSIDE_STEPS = {-20, -60};

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
        return forChunk(coreStart, cycle.legacyCoreLenBlocks(LegacyBandKind.FAR_LANDS, chunkX << 4), chunkX, chunkZ);
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
            case CLOSING -> left ? leftWall(SIDE_Z)
                    : rightWall(closingDistance((p - ENTRY_END) / (CLOSING_END - ENTRY_END)));
            case CANYON -> left ? leftWall(SIDE_Z) : rightWall(SIDE_Z);
            case OPENING -> left ? leftWall(openingDistance((p - CANYON_END) / (OPENING_END - CANYON_END)))
                    : rightWall(SIDE_Z);
            case EXIT -> p < SWEEP_BESIDE_END ? rightWall(SWEEP_BESIDE_Z)
                    : p < SWEEP_END ? rightWall(step(SWEEP_INSIDE_STEPS, p - SWEEP_BESIDE_END, SWEEP_INSIDE_STEP))
                    // Negative X edge: overflowed until the exit wall, ordinary land after it.
                    : xEdge(-(long) EDGE - worldX(coreStartX, holdLen, EXIT_WALL));
        };
    }

    /** World X of script block {@code script}. */
    private static long worldX(long coreStartX, long holdLen, int script) {
        return coreStartX + (holdLen <= 0L ? script : Math.round((double) script * holdLen / SCRIPT_LEN));
    }

    /** Closing wall's distance from the track at {@code t} (0 → 1) through the stage: fast in, then creeping. */
    public static int closingDistance(double t) {
        double u = 1.0 - Math.max(0.0, Math.min(1.0, t));
        return (int) Math.round(SIDE_Z + (FAR_Z - SIDE_Z) * Math.pow(u, EASE_POWER));
    }

    /** Opening wall's distance at {@code t}: the closing ease run backwards — creeping off, then rushing out. */
    public static int openingDistance(double t) {
        return closingDistance(1.0 - t);
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
