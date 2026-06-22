package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;

/**
 * The single repeating world-gen cycle the train crosses, laying out BOTH special
 * phases in one fixed order along +X from a shared anchor:
 *
 * <pre>
 *   OW → Nether transition → Nether → Nether transition → OW → Void → End islands → Void → (repeat)
 * </pre>
 *
 * i.e. per period: {@code [owGap] [nether band] [owGap] [end band]}. The two sub-bands
 * reuse the existing ramp math ({@link NetherTransition} and {@link Disintegration})
 * evaluated at a <em>local</em> offset with {@code owHold = 0}, so this class only owns
 * the layout/positioning — the ramp shapes are unchanged.
 *
 * <p>Pure (no Minecraft types) so the layout is unit-testable; the {@link #fromConfig()}
 * factory is the one runtime convenience that reads COMMON config (readable on both
 * server and client). The per-world {@code startsWithTrain} gate lives in the callers
 * ({@code NetherBand} / {@code DisintegrationBand} / the client band caches).</p>
 *
 * @param startX   world-X the cycle is anchored at (before it: plain overworld)
 * @param owGap    overworld blocks before each special band (two gaps per period)
 * @param nFade    nether mountain rise/fall span
 * @param nMtn     nether full-height mountain plateau span (each side)
 * @param nCoreFade nether stone→netherrack crossfade span (each side)
 * @param nCore    real-Nether core span
 * @param eFade    End fade span
 * @param eVoid    End void-hold span (each side)
 * @param eEnd     End-island core span
 */
public record WorldGenCycle(long startX, int owGap,
                            int nFade, int nMtn, int nCoreFade, int nCore,
                            int eFade, int eVoid, int eEnd) {

    /** Build from live COMMON config; a disabled phase collapses to zero length (just overworld). */
    public static WorldGenCycle fromConfig() {
        boolean nether = DungeonTrainCommonConfig.isNetherTransitionEnabled();
        boolean end = DungeonTrainCommonConfig.isDisintegrationEnabled();
        return new WorldGenCycle(
                DungeonTrainCommonConfig.getDisintegrationStartBlocks(),
                DungeonTrainCommonConfig.getDisintegrationOverworldHoldBlocks(),
                nether ? DungeonTrainCommonConfig.getNetherFadeBlocks() : 0,
                nether ? DungeonTrainCommonConfig.getNetherMountainHoldBlocks() : 0,
                nether ? DungeonTrainCommonConfig.getNetherCoreFadeBlocks() : 0,
                nether ? DungeonTrainCommonConfig.getNetherCoreHoldBlocks() : 0,
                end ? DungeonTrainCommonConfig.getDisintegrationFadeBlocks() : 0,
                end ? DungeonTrainCommonConfig.getDisintegrationVoidHoldBlocks() : 0,
                end ? DungeonTrainCommonConfig.getDisintegrationEndHoldBlocks() : 0);
    }

    public long netherLen() {
        return NetherTransition.bandLength(nFade, nMtn, nCoreFade, nCore);
    }

    public long endLen() {
        return Disintegration.bandLength(eFade, eVoid, eEnd);
    }

    /** {@code 2·owGap + netherLen + endLen}; 0 if everything collapses (nothing to generate). */
    public long period() {
        return 2L * Math.max(0, owGap) + netherLen() + endLen();
    }

    /** Offset into the current cycle, or {@code -1} before the anchor / when the cycle is empty. */
    private long offset(int worldX) {
        long p = period();
        if (p <= 0L || worldX < startX) return -1L;
        return Math.floorMod((long) worldX - startX, p);
    }

    private long netherStart() {
        return Math.max(0, owGap);
    }

    private long endStart() {
        return 2L * Math.max(0, owGap) + netherLen();
    }

    /** Nether mountain-height ramp at a world-X (0 outside the nether segment). */
    public double netherHeightRamp(int worldX) {
        long o = offset(worldX);
        if (o < 0L) return 0.0;
        long ln = o - netherStart();
        if (ln < 0L || ln >= netherLen()) return 0.0;
        return NetherTransition.heightRamp((int) ln, 0L, nFade, nMtn, nCoreFade, nCore, 0);
    }

    /** Nether intensity ramp (netherrack → real Nether) at a world-X (0 outside the nether segment). */
    public double netherRamp(int worldX) {
        long o = offset(worldX);
        if (o < 0L) return 0.0;
        long ln = o - netherStart();
        if (ln < 0L || ln >= netherLen()) return 0.0;
        return NetherTransition.netherRamp((int) ln, 0L, nFade, nMtn, nCoreFade, nCore, 0);
    }

    /** Portion of the rise (after reaching normal height) spent climbing back up to full height. */
    private static final double RISE_TO_NORMAL_FRAC = 0.4;

    /**
     * Stamped mountain top-Y for a world-X column. The rise holds at a <em>normal</em>
     * vanilla-mountain height ({@code bedY + baseHeight}) for {@code normalHold} blocks
     * (stage 1, the real-biome look) before climbing to the world-height mega-mountain
     * (stages 2–3); the interior holds at full height and the exit mirrors the rise.
     * Returns {@code bedY} outside the nether segment.
     */
    public int netherMountainTopY(int worldX, int bedY, int maxHeight, int baseHeight, int normalHold, int worldTop) {
        long o = offset(worldX);
        if (o < 0L) return bedY;
        long ln = o - netherStart();
        long band = netherLen();
        if (ln < 0L || ln >= band) return bedY;
        double baseFrac = maxHeight <= 0 ? 0.0 : clamp01((double) baseHeight / maxHeight);
        double h = shapedRiseFraction(ln, band, nFade, normalHold, baseFrac);
        int top = bedY + (int) Math.round(h * Math.max(0, maxHeight));
        return Math.min(worldTop, top);
    }

    /**
     * Height fraction {@code [0,1]} of the shaped mountain envelope at band-offset {@code ln}:
     * 0→baseFrac, hold baseFrac for {@code normalHold}, baseFrac→1 over the rest of the
     * leading fade; flat 1 across the interior; mirrored fall.
     */
    private static double shapedRiseFraction(long ln, long band, int fade, int normalHold, double baseFrac) {
        int f = Math.max(0, fade);
        if (f == 0) return 1.0;                                   // no fade → full height immediately
        if (ln < f) return riseShape(ln, f, normalHold, baseFrac);
        if (ln < band - f) return 1.0;                            // interior mega-mountain
        long df = ln - (band - f);                                 // 0..f into the fall
        return riseShape(f - df, f, normalHold, baseFrac);        // mirror the rise
    }

    /** The rise curve over {@code [0,f]}: ramp to baseFrac, hold, ramp to 1. */
    private static double riseShape(long x, int f, int normalHold, double baseFrac) {
        if (x <= 0L) return 0.0;
        if (x >= f) return 1.0;
        int nh = Math.max(0, Math.min(normalHold, f));
        double remaining = f - nh;
        double a = remaining * RISE_TO_NORMAL_FRAC;               // blocks spent rising 0→baseFrac
        if (a > 0.0 && x < a) return baseFrac * (x / a);          // rise to normal
        if (x < a + nh) return baseFrac;                          // hold normal (stage 1)
        double climb = f - (a + nh);                              // blocks spent rising baseFrac→1
        if (climb <= 0.0) return 1.0;
        return baseFrac + (1.0 - baseFrac) * (x - (a + nh)) / climb;
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    /** End erosion / sky ramp at a world-X (0 outside the End segment). */
    public double endMiddleRamp(int worldX) {
        long o = offset(worldX);
        if (o < 0L) return 0.0;
        long le = o - endStart();
        if (le < 0L || le >= endLen()) return 0.0;
        return Disintegration.middleRamp((int) le, 0L, eFade, eVoid, eEnd, 0);
    }

    /** End-island fill ramp at a world-X (0 outside the End segment). */
    public double endIslandRamp(int worldX) {
        long o = offset(worldX);
        if (o < 0L) return 0.0;
        long le = o - endStart();
        if (le < 0L || le >= endLen()) return 0.0;
        return Disintegration.endRamp((int) le, 0L, eFade, eVoid, eEnd, 0);
    }
}
