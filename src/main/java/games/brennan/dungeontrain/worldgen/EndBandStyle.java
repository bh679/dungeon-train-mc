package games.brennan.dungeontrain.worldgen;

/**
 * Which look an End-islands band pass gets. The band alternates: the 1st, 3rd, 5th… crossing (pass
 * index 0, 2, 4 — see {@link WorldGenCycle#endPassIndex}) is the vanilla End — end stone stamped in
 * the shape of the real End density, chorus and End cities ({@code DisintegrationFeature},
 * {@code BandEndCityStructure}). The 2nd, 4th, 6th… crossing is the <b>BetterEnd</b> End: real End
 * chunks — which BetterEnd: New Dawn fills with its biomes, terrain and plants — copied onto track
 * level off-thread ({@link EndBandSampler}, written in by {@code WorldEndBandEvents}).
 *
 * <p>Also owns the End ↔ display coordinate mapping those real-chunk samples use, so the sampled
 * terrain lines up with the biome {@code EndCoreBiomes} labels the column with: both read the outer
 * End at {@code worldX + ISLAND_SAMPLE_OFFSET_X + pass × PASS_STEP_X}.</p>
 *
 * <p>Pure logic — no Minecraft state — so it is unit-tested directly.</p>
 */
public final class EndBandStyle {

    /** Blocks each successive End-band pass advances outward in the real End (shared with EndCoreBiomes). */
    public static final int PASS_STEP_X = 4_000;

    /**
     * Edge-fade cut-off for sampled blocks. Columns at or past this island ramp keep every sampled block;
     * below it (the band's void margins) blocks are dithered away, so the BetterEnd terrain crumbles into
     * the void the same way the vanilla islands thin out.
     */
    public static final double FULL_FILL_RAMP = 1.0;

    private EndBandStyle() {}

    /** True if End-band pass {@code passIndex} is a BetterEnd pass (odd: the 2nd, 4th, 6th… crossing). */
    public static boolean isBetterEndPass(long passIndex) {
        return passIndex >= 0L && (passIndex & 1L) == 1L;
    }

    /**
     * End-space X sampled for display column {@code worldX} on pass {@code passIndex}. Negative passes
     * (before the cycle) sample as pass 0.
     */
    public static long endSampleX(int worldX, long passIndex) {
        long pass = Math.max(0L, passIndex);
        return (long) worldX + EndIslandGeometry.ISLAND_SAMPLE_OFFSET_X + pass * (long) PASS_STEP_X;
    }

    /**
     * Chunk-X offset from a display chunk to the End chunk it copies. Exact because both the fixed
     * offset and the per-pass step are multiples of 16, so a display chunk maps onto exactly one End chunk.
     */
    public static int endChunkOffsetX(long passIndex) {
        long blocks = endSampleX(0, passIndex);
        return Math.toIntExact(Math.floorDiv(blocks, 16L));
    }

    /** Display (track-level) Y for End-space Y {@code endY}: End Y {@code END_ISLAND_CENTER_Y} lands on {@code bedY}. */
    public static int displayY(int endY, int bedY) {
        return bedY + (endY - EndIslandGeometry.END_ISLAND_CENTER_Y);
    }

    /** Inverse of {@link #displayY}. */
    public static int endY(int displayY, int bedY) {
        return EndIslandGeometry.END_ISLAND_CENTER_Y + (displayY - bedY);
    }

    /**
     * Whether a sampled block survives at a column with island ramp {@code ramp}: always in the band
     * core, never outside it, and in the fade with probability {@code ramp} (compared against a coherent
     * noise value in [0, 1), so the thinning is clumpy rather than salt-and-pepper).
     */
    public static boolean keepSampledBlock(double ramp, double noise01) {
        if (ramp <= 0.0) return false;
        if (ramp >= FULL_FILL_RAMP) return true;
        return noise01 < ramp;
    }
}
