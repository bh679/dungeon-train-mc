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
 * the layout/positioning.
 *
 * <p>The nether mountain runs in three stages that progressively amplify the <em>natural
 * overworld heightmap</em>: stage 1 ×1 (a real-looking vanilla mountain biome), stage 2
 * ×{@code stage2Mult}, stage 3 ×{@code stage3Mult} (the mega-mountain). The per-X
 * multiplier is {@link #netherMountainMultiplier}; the feature multiplies each column's
 * natural height (above sea level) by it.</p>
 *
 * <p>Pure (no Minecraft types) so the layout is unit-testable; {@link #fromConfig()} is
 * the one runtime convenience that reads COMMON config. The per-world
 * {@code startsWithTrain} gate lives in the callers.</p>
 *
 * @param startX     world-X the cycle is anchored at (before it: plain overworld)
 * @param owGap      overworld blocks before each special band (two gaps per period)
 * @param stageBlocks length of EACH mountain stage
 * @param stageMultipliers heightmap multiplier per stage (stage 1 = first value, 1 = natural)
 * @param beachBlocks leading beach/cliff span (rendered as beach only over ocean; base multiplier 1)
 * @param megaHold   full-strength mega-mountain plateau on each side of the core
 * @param coreFade   mountain→netherrack crossfade span (each side)
 * @param coreHold   real-Nether core span
 * @param eFade      End fade span
 * @param eVoid      End void-hold span (each side)
 * @param eEnd       End-island core span
 * @param phaseShift blocks the whole cycle is shifted at {@code startX} so the FIRST overworld gap
 *                   (to the nether band) is shorter than the recurring {@code owGap}; {@code
 *                   max(0, owGap − firstOverworld)}, 0 = no shift. Shared with the End band's
 *                   disintegration phase-shift so both layouts stay in lock-step.
 */
public record WorldGenCycle(long startX, int owGap,
                            int stageBlocks, int[] stageMultipliers, int beachBlocks, int megaHold,
                            int coreFade, int coreHold,
                            int eFade, int eVoid, int eEnd, int phaseShift) {

    /** Build from live COMMON config; a disabled phase collapses to zero length (just overworld). */
    public static WorldGenCycle fromConfig() {
        boolean nether = DungeonTrainCommonConfig.isNetherTransitionEnabled();
        boolean end = DungeonTrainCommonConfig.isDisintegrationEnabled();
        return new WorldGenCycle(
                DungeonTrainCommonConfig.getDisintegrationStartBlocks(),
                DungeonTrainCommonConfig.getDisintegrationOverworldHoldBlocks(),
                nether ? DungeonTrainCommonConfig.getNetherStageBlocks() : 0,
                DungeonTrainCommonConfig.getNetherStageMultipliers(),
                nether ? DungeonTrainCommonConfig.getNetherBeachBlocks() : 0,
                nether ? DungeonTrainCommonConfig.getNetherMountainHoldBlocks() : 0,
                nether ? DungeonTrainCommonConfig.getNetherCoreFadeBlocks() : 0,
                nether ? DungeonTrainCommonConfig.getNetherCoreHoldBlocks() : 0,
                end ? DungeonTrainCommonConfig.getDisintegrationFadeBlocks() : 0,
                end ? DungeonTrainCommonConfig.getDisintegrationVoidHoldBlocks() : 0,
                end ? DungeonTrainCommonConfig.getDisintegrationEndHoldBlocks() : 0,
                DungeonTrainCommonConfig.getDisintegrationPhaseShiftBlocks());
    }

    private int stageCount() {
        return (stageMultipliers == null || stageMultipliers.length == 0) ? 1 : stageMultipliers.length;
    }

    private double stageMult(int i) {
        if (stageMultipliers == null || stageMultipliers.length == 0) return 1.0;
        int idx = Math.max(0, Math.min(i, stageMultipliers.length - 1));
        return Math.max(1, stageMultipliers[idx]);
    }

    /** Combined length of the leading beach span + all mountain stages (the heightRamp's rise/fall span). */
    public int riseLen() {
        return Math.max(0, beachBlocks) + stageCount() * Math.max(0, stageBlocks);
    }

    public long netherLen() {
        return NetherTransition.bandLength(riseLen(), megaHold, coreFade, coreHold);
    }

    public long endLen() {
        return Disintegration.bandLength(eFade, eVoid, eEnd);
    }

    /** {@code 2·owGap + netherLen + endLen}; 0 if everything collapses (nothing to generate). */
    public long period() {
        return 2L * Math.max(0, owGap) + netherLen() + endLen();
    }

    /**
     * Offset into the current cycle, or {@code -1} before the anchor / when the cycle is empty.
     * {@code phaseShift} lands {@code startX} that many blocks into the cycle (applied after the
     * before-anchor guard), so the first overworld gap to the nether band is shortened by it.
     */
    private long offset(int worldX) {
        long p = period();
        if (p <= 0L || worldX < startX) return -1L;
        return Math.floorMod((long) worldX - startX + phaseShift, p);
    }

    private long netherStart() {
        return Math.max(0, owGap);
    }

    private long endStart() {
        return 2L * Math.max(0, owGap) + netherLen();
    }

    private long netherOffset(int worldX) {
        long o = offset(worldX);
        if (o < 0L) return -1L;
        long ln = o - netherStart();
        return (ln < 0L || ln >= netherLen()) ? -1L : ln;
    }

    /** Nether band-presence ramp (0 outside the nether segment) — drives in-band gating. */
    public double netherHeightRamp(int worldX) {
        long ln = netherOffset(worldX);
        if (ln < 0L) return 0.0;
        return NetherTransition.heightRamp((int) ln, 0L, riseLen(), megaHold, coreFade, coreHold, 0);
    }

    /** Nether intensity ramp (netherrack → real Nether) at a world-X (0 outside the nether segment). */
    public double netherRamp(int worldX) {
        long ln = netherOffset(worldX);
        if (ln < 0L) return 0.0;
        return NetherTransition.netherRamp((int) ln, 0L, riseLen(), megaHold, coreFade, coreHold, 0);
    }

    /**
     * netherRamp at/above which a column is the real-Nether <b>core</b> (full netherrack/lava sampling,
     * not the netherrack crossfade). Shared by {@code NetherTransitionFeature} (which REPLACES core
     * columns with sampled Nether terrain) and the biome-source mixin (which tags core columns as the
     * real {@code nether_wastes} biome).
     */
    public static final double NETHER_CORE_THRESHOLD = 0.999;

    /** True if {@code worldX} is a real-Nether core column ({@link #netherRamp} ≥ {@link #NETHER_CORE_THRESHOLD}). */
    public boolean isNetherCore(int worldX) {
        return netherRamp(worldX) >= NETHER_CORE_THRESHOLD;
    }

    /**
     * Heightmap multiplier at a world-X: 1 outside the nether segment, {@code 1} across
     * stage 1, ramping {@code 1→stage2Mult} across stage 2, {@code stage2Mult→stage3Mult}
     * across stage 3, then held at {@code stage3Mult} across the mega plateau + core, and
     * mirrored on the exit.
     */
    public double netherMountainMultiplier(int worldX) {
        long ln = netherOffset(worldX);
        if (ln < 0L) return 1.0;
        long band = netherLen();
        int rise = riseLen();
        long edge = rise + Math.max(0, megaHold);
        if (ln < edge) return riseMult(ln, rise);
        if (ln >= band - edge) return riseMult(band - ln, rise);
        return stageMult(stageCount() - 1);                        // core region: last (mega) held
    }

    /**
     * Stage curve over the rise: the leading beach span holds the natural ×1 (the feature
     * boosts it over ocean), then each mountain stage ramps from the previous multiplier
     * to its own (stage 1 ramps from ×1), so the mountains grow smoothly stage by stage;
     * the final multiplier is held across the mega plateau.
     */
    private double riseMult(long d, int rise) {
        int n = stageCount();
        if (d >= rise) return stageMult(n - 1);                    // mega plateau
        int beach = Math.max(0, beachBlocks);
        if (d < beach) return 1.0;                                 // beach span — base ×1 (feature overrides over ocean)
        long md = d - beach;                                       // offset into the mountain stages
        int s = Math.max(1, stageBlocks);
        int idx = (int) (md / s);
        if (idx >= n) return stageMult(n - 1);
        double from = (idx == 0) ? 1.0 : stageMult(idx - 1);
        double to = stageMult(idx);
        double within = (double) (md - (long) idx * s) / s;
        return from + (to - from) * within;
    }

    /** True if {@code worldX} lies in the leading beach span of a nether band (the ocean-entry stretch). */
    public boolean isNetherBeachStage(int worldX) {
        long ln = netherOffset(worldX);
        return ln >= 0 && ln < Math.max(0, beachBlocks);
    }

    /**
     * Progress {@code 0..1} across the leading nether beach span: {@code 0} at the seaward entrance
     * edge (the ocean waterline) climbing to {@code 1} at the inland edge where the beach meets the
     * mountains — so a shore ramp can be drawn across it. Clamped to {@code [0,1]}; outside the beach
     * span the value is meaningless, so callers must gate on {@link #isNetherBeachStage}.
     */
    public double netherBeachProgress(int worldX) {
        int beach = Math.max(0, beachBlocks);
        if (beach == 0) return 0.0;
        long ln = netherOffset(worldX);
        if (ln <= 0L) return 0.0;
        double p = (double) ln / beach;
        return p < 0.0 ? 0.0 : (p > 1.0 ? 1.0 : p);
    }

    /** World-X where the current nether band's rise begins (for ocean detection), or {@code Long.MIN_VALUE}. */
    public long netherBandEntranceX(int worldX) {
        long o = offset(worldX);
        if (o < 0L) return Long.MIN_VALUE;
        return (long) worldX - o + netherStart();
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

    /**
     * End sky/fog ramp at a world-X — like {@link #endMiddleRamp} but with both fades pushed
     * {@code skyOffset} blocks toward the void core, so the End sky lags the terrain erosion
     * (delayed fade-in on entry, early fade-out on exit). 0 outside the End segment;
     * {@code skyOffset == 0} reproduces {@link #endMiddleRamp} exactly.
     */
    public double endSkyRamp(int worldX, int skyOffset) {
        long o = offset(worldX);
        if (o < 0L) return 0.0;
        long le = o - endStart();
        if (le < 0L || le >= endLen()) return 0.0;
        return Disintegration.skyRamp((int) le, 0L, eFade, eVoid, eEnd, 0, skyOffset);
    }
}
