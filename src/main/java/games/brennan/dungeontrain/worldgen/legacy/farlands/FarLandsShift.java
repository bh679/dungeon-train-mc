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
 * <p><b>Four stages</b>, a quarter of the core each (the train rides +X, so its left is −Z):</p>
 * <ol>
 *   <li>{@link Stage#WALL} — source X is {@code L + EDGE − APPROACH}: {@value #APPROACH} blocks of ordinary
 *       Beta land, then the X edge wall crosses the track and the train rides the edge lands. The entry
 *       fade uses this stage.</li>
 *   <li>{@link Stage#LEFT} — X back in range; the negative Z edge sits {@value #SIDE_Z} blocks to the
 *       track's left, so the Far Lands rise on the left, their wall facing the track.</li>
 *   <li>{@link Stage#BOTH} — chunks left of the track read the negative Z edge, chunks right of it the
 *       positive one: a Far Lands wall on each side, the train in the canyon between.</li>
 *   <li>{@link Stage#RIGHT} — the positive Z edge {@value #SIDE_Z} blocks to the right, facing the track.
 *       The exit fade uses this stage.</li>
 * </ol>
 *
 * <p>Shifts are whole chunks, constant within a stage of one band instance and (in {@link Stage#BOTH}) one
 * side of the track, so stages and sides meet on chunk boundaries as the band's chunk walls.</p>
 *
 * @param dxChunks source chunk X minus world chunk X
 * @param dzChunks source chunk Z minus world chunk Z
 */
public record FarLandsShift(int dxChunks, int dzChunks) {

    /** First block where Beta's top limit-noise octave overflows ({@code 2³¹ / 171.103}). */
    public static final int EDGE = 12_550_824;
    /** Ordinary Beta land at the start of the core before the edge wall. */
    public static final int APPROACH = 256;
    /** Distance of a side wall from the track ({@code z = 0}) in the side stages. */
    public static final int SIDE_Z = 48;

    public static final FarLandsShift NONE = new FarLandsShift(0, 0);

    /** The band's stages, in riding order; each holds a quarter of the core. */
    public enum Stage { WALL, LEFT, BOTH, RIGHT }

    // Mirror images, so both side walls stand the same distance from the track.
    private static final int RIGHT_DZ = Math.floorDiv(EDGE - SIDE_Z, 16);
    private static final int LEFT_DZ = -RIGHT_DZ;

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

    /** The stage at world chunk {@code chunkX} of a band whose core starts at {@code coreStartX}. */
    public static Stage stageAt(long coreStartX, long holdLen, int chunkX) {
        long local = ((long) chunkX << 4) - coreStartX;
        if (local < 0L || holdLen <= 0L) return Stage.WALL;
        int quarter = (int) Math.min(3L, local * 4L / holdLen);
        return Stage.values()[quarter];
    }

    /** Pure form of {@link #of}. */
    public static FarLandsShift forChunk(long coreStartX, long holdLen, int chunkX, int chunkZ) {
        return switch (stageAt(coreStartX, holdLen, chunkX)) {
            case WALL -> new FarLandsShift((int) Math.floorDiv((long) EDGE - APPROACH - coreStartX, 16L), 0);
            case LEFT -> new FarLandsShift(0, LEFT_DZ);
            case BOTH -> new FarLandsShift(0, chunkZ < 0 ? LEFT_DZ : RIGHT_DZ);
            case RIGHT -> new FarLandsShift(0, RIGHT_DZ);
        };
    }
}
