package games.brennan.dungeontrain.worldgen.legacy.farlands;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;

/**
 * Where a Far Lands band chunk reads Beta's terrain from: the band is the ordinary Beta generator
 * ({@link games.brennan.dungeontrain.worldgen.legacy.beta.BetaTerrain}) sampled ~12.55 million blocks out,
 * with the world chunk translated by {@code (dxChunks, dzChunks)}.
 *
 * <p><b>Why that far.</b> Beta's two 16-octave limit noises sample at {@code 684.412} noise units per
 * 4-block cell (171.103 per block). Beta floors each coordinate with {@code (int) d}, which saturates at
 * {@code Integer.MAX_VALUE} once {@code 171.103 × x} passes 2³¹ — at {@value #EDGE} blocks. From there the
 * top octave's lattice cell stops changing while the in-cell fraction grows without bound, so its
 * interpolation explodes: the Far Lands. Our port keeps both halves of that bug ({@code LegacyMath.floor}
 * is the same saturating cast, and the octave stack has no Beta 1.8 {@code % 16777216} wrap), so no noise
 * change is needed — only the coordinates.</p>
 *
 * <p><b>Layout along the band</b> ({@code L} = blocks past the band core's start):</p>
 * <ul>
 *   <li>Act 1 — source X is {@code L + EDGE − APPROACH}: {@value #APPROACH} blocks of ordinary Beta land,
 *       then the <i>edge</i> wall crosses the track and the train rides the edge lands, whose tunnels run
 *       out along +X, the way the train travels.</li>
 *   <li>Act 2 — from {@link #ACT2_FRACTION} of the core onward (through the exit fade), source Z also jumps
 *       so the Z overflow edge sits {@value #CORNER_SIDE_Z} blocks to the +Z side of the track: that side
 *       becomes the <i>corner</i> lands (both axes overflowed) while the train's side stays edge lands.</li>
 * </ul>
 *
 * <p>Both shifts are whole chunks and constant within one band instance (act 2 switches on a chunk
 * boundary), so every world chunk maps to one source chunk and neighbours stay seamless.</p>
 *
 * @param dxChunks source chunk X minus world chunk X
 * @param dzChunks source chunk Z minus world chunk Z
 */
public record FarLandsShift(int dxChunks, int dzChunks) {

    /** First block where Beta's top limit-noise octave overflows ({@code 2³¹ / 171.103}). */
    public static final int EDGE = 12_550_824;
    /** Ordinary Beta land at the start of the core before the edge wall. */
    public static final int APPROACH = 256;
    /** Share of the core ridden before act 2 (corner lands beside the track) begins. */
    public static final double ACT2_FRACTION = 2.0 / 3.0;
    /** World Z of the Z overflow edge in act 2 — just past the corridor's +Z side. */
    public static final int CORNER_SIDE_Z = 48;

    public static final FarLandsShift NONE = new FarLandsShift(0, 0);

    /** Source block X offset. */
    public int dxBlocks() {
        return dxChunks << 4;
    }

    /** Source block Z offset. */
    public int dzBlocks() {
        return dzChunks << 4;
    }

    /** The shift for world chunk {@code chunkX} under the live cycle; {@link #NONE} outside the band's slot. */
    public static FarLandsShift of(WorldGenCycle cycle, int chunkX) {
        int blockX = chunkX << 4;
        long coreStart = cycle.legacyCoreStartX(LegacyBandKind.FAR_LANDS, blockX);
        if (coreStart == WorldGenCycle.NOT_IN_LEGACY_SLOT) return NONE;
        return forChunk(coreStart, cycle.legacyLen(LegacyBandKind.FAR_LANDS), chunkX);
    }

    /** Pure form: the shift for world chunk {@code chunkX} of a band whose core starts at {@code coreStartX}. */
    public static FarLandsShift forChunk(long coreStartX, long holdLen, int chunkX) {
        int dx = (int) Math.floorDiv((long) EDGE - APPROACH - coreStartX, 16L);
        long local = ((long) chunkX << 4) - coreStartX;
        boolean act2 = local >= (long) (holdLen * ACT2_FRACTION);
        int dz = act2 ? Math.floorDiv(EDGE - CORNER_SIDE_Z, 16) : 0;
        return new FarLandsShift(dx, dz);
    }
}
