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
 * i.e. per period: {@code [owGapBeforeNether] [nether band] [owGapBeforeEnd] [end band]}. The two
 * overworld gaps differ — {@code owGapBeforeNether} (Void→Nether, the longer) and
 * {@code owGapBeforeEnd} (Nether→Void, the shorter) alternate. The two sub-bands reuse the existing
 * ramp math ({@link NetherTransition} and {@link Disintegration}) evaluated at a <em>local</em>
 * offset with {@code owHold = 0}, so this class only owns the layout/positioning.
 *
 * <h2>Per-period growth</h2>
 * <p>Each successive period is larger: period 0 uses the configured base lengths, and every later
 * period multiplies the <b>scaled</b> segments — the Nether core ({@code coreHold}), void hold
 * ({@code eVoid}), End-islands core ({@code eEnd}), and BOTH overworld gaps — by {@code growthFactor}
 * (period 1 ×{@code growthFactor}, period 2 ×{@code growthFactor²}, …). The transition <b>fades</b>
 * (mountain rise/fall {@link #riseLen()}, mega plateau {@code megaHold}, netherrack crossfade
 * {@code coreFade}, void↔End fade {@code eFade}) stay fixed at every period. {@code growthFactor == 1}
 * reproduces the classic uniform cycle. Growth is geometric but bounded in practice by the world
 * border (and hard-clamped so scaled spans never overflow {@code int}).</p>
 *
 * <p>The nether mountain runs in stages that progressively amplify the <em>natural overworld
 * heightmap</em>: stage 1 ×1 (a real-looking vanilla mountain biome), then each successive stage
 * ramps to its multiplier, the last held across the mega-mountain. The per-X multiplier is
 * {@link #netherMountainMultiplier}; the feature multiplies each column's natural height (above sea
 * level) by it.</p>
 *
 * <p>Pure (no Minecraft types) so the layout is unit-testable; {@link #fromConfig()} is the one
 * runtime convenience that reads COMMON config. The per-world {@code startsWithTrain} gate lives in
 * the callers.</p>
 *
 * @param startX            world-X the cycle is anchored at (before it: plain overworld)
 * @param owGapBeforeNether overworld blocks before the Nether band (Void→Nether gap); scales per period
 * @param owGapBeforeEnd    overworld blocks before the End band (Nether→Void gap); scales per period
 * @param growthFactor      per-period multiplier for the scaled segments (1 = no growth)
 * @param stageBlocks       length of EACH mountain stage (fixed)
 * @param stageMultipliers  heightmap multiplier per stage (stage 1 = first value, 1 = natural)
 * @param beachBlocks       leading beach/cliff span (rendered as beach only over ocean; base multiplier 1)
 * @param megaHold          full-strength mega-mountain plateau on each side of the core (fixed)
 * @param coreFade          mountain→netherrack crossfade span (each side; fixed)
 * @param coreHold          real-Nether core span (scales per period)
 * @param eFade             End fade span (fixed)
 * @param eVoid             End void-hold span (each side; scales per period)
 * @param eEnd              End-island core span (scales per period)
 * @param phaseShift        blocks the cycle is shifted at {@code startX} so the FIRST overworld gap
 *                          (to the nether band) is shorter than the recurring {@code owGapBeforeNether};
 *                          {@code max(0, owGapBeforeNether − firstOverworld)}, 0 = no shift. Applied to
 *                          period 0 only (the spawn leg).
 */
public record WorldGenCycle(long startX, int owGapBeforeNether, int owGapBeforeEnd, int growthFactor,
                            int stageBlocks, int[] stageMultipliers, int beachBlocks, int megaHold,
                            int coreFade, int coreHold,
                            int eFade, int eVoid, int eEnd, int phaseShift) {

    /** Loop-safety bound on the period locator (far beyond any reachable world-X). */
    private static final int MAX_PERIODS = 64;
    /** Scale ceiling so scaled spans/positions never approach overflow; ~1M periods out, past the border. */
    private static final long MAX_SCALE = 1L << 20;

    /** Build from live COMMON config; a disabled phase collapses to zero length (just overworld). */
    public static WorldGenCycle fromConfig() {
        boolean nether = DungeonTrainCommonConfig.isNetherTransitionEnabled();
        boolean end = DungeonTrainCommonConfig.isDisintegrationEnabled();
        return new WorldGenCycle(
                DungeonTrainCommonConfig.getDisintegrationStartBlocks(),
                DungeonTrainCommonConfig.getDisintegrationOverworldHoldBlocks(),          // before Nether (g1)
                DungeonTrainCommonConfig.getDisintegrationOverworldHoldBeforeEndBlocks(), // before End (g2)
                DungeonTrainCommonConfig.getDisintegrationPeriodGrowthFactor(),
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

    private int growth() {
        return Math.max(1, growthFactor);
    }

    /** A scaled hold span, clamped so band sums and band-local offsets stay within {@code int}. */
    private static int scaledHold(int base, long scale) {
        long v = (long) Math.max(0, base) * Math.max(1L, scale);
        return (int) Math.min(v, (long) Integer.MAX_VALUE / 8L);
    }

    /** Nether band length at a given period scale: only {@code coreHold} grows; the fades are fixed. */
    private long netherLenAt(long scale) {
        return NetherTransition.bandLength(riseLen(), megaHold, coreFade, scaledHold(coreHold, scale));
    }

    /** End band length at a given period scale: {@code eVoid} and {@code eEnd} grow; {@code eFade} is fixed. */
    private long endLenAt(long scale) {
        return Disintegration.bandLength(eFade, scaledHold(eVoid, scale), scaledHold(eEnd, scale));
    }

    private long gapBeforeNether(long scale) {
        return (long) Math.max(0, owGapBeforeNether) * Math.max(1L, scale);
    }

    private long gapBeforeEnd(long scale) {
        return (long) Math.max(0, owGapBeforeEnd) * Math.max(1L, scale);
    }

    /** Full length of the period at a given scale: {@code g1 + nether + g2 + end} (all scaled as applicable). */
    private long periodLenAt(long scale) {
        return gapBeforeNether(scale) + netherLenAt(scale) + gapBeforeEnd(scale) + endLenAt(scale);
    }

    /** Base (period-0) Nether band length — also the in-band guard used by {@code NetherBand}. */
    public long netherLen() {
        return netherLenAt(1L);
    }

    /** Base (period-0) End band length — also the in-band guard used by {@code DisintegrationBand}. */
    public long endLen() {
        return endLenAt(1L);
    }

    /**
     * Base (period-0) period length; {@code 0} if everything collapses (nothing to generate). Later
     * periods grow by {@code growthFactor}; this base value is only used as a {@code > 0} enable-guard
     * and by the unit tests.
     */
    public long period() {
        return periodLenAt(1L);
    }

    /** A located column: which period (scale), the offset within it, and that period's band boundaries. */
    private record Loc(long scale, long local, long netherStart, long netherLen, long endStart, long endLen) {}

    /**
     * Locate {@code worldX} within the (possibly growing) cycle: {@code null} before the anchor or when
     * the cycle is empty. {@code phaseShift} lands the anchor that many blocks into period 0, so the
     * first overworld gap to the nether band is shortened by it. With {@code growthFactor == 1} the
     * cycle is uniform (O(1) {@code floorMod}); otherwise periods grow geometrically and the locator
     * walks them (≤ {@link #MAX_PERIODS} steps for any reachable world-X).
     */
    private Loc locate(int worldX) {
        long base = periodLenAt(1L);
        if (base <= 0L || worldX < startX) return null;
        long d = (long) worldX - startX + Math.max(0, phaseShift);
        if (d < 0L) return null;

        int gf = growth();
        if (gf == 1) {
            return locFor(1L, Math.floorMod(d, base));
        }
        long cursor = 0L;
        long scale = 1L;
        for (int k = 0; k < MAX_PERIODS; k++) {
            long plen = periodLenAt(scale);
            if (plen <= 0L) return null;
            if (d < cursor + plen) {
                return locFor(scale, d - cursor);
            }
            cursor += plen;
            scale = Math.min(scale * gf, MAX_SCALE);
        }
        return null; // unreachable for any in-bounds world-X
    }

    private Loc locFor(long scale, long local) {
        long g1 = gapBeforeNether(scale);
        long nlen = netherLenAt(scale);
        long g2 = gapBeforeEnd(scale);
        long elen = endLenAt(scale);
        long netherStart = g1;
        long endStart = g1 + nlen + g2;
        return new Loc(scale, local, netherStart, nlen, endStart, elen);
    }

    /** Band-local offset into the nether band, or {@code -1} if {@code worldX} isn't in it. */
    private static long netherBandOffset(Loc l) {
        if (l == null) return -1L;
        long ln = l.local() - l.netherStart();
        return (ln < 0L || ln >= l.netherLen()) ? -1L : ln;
    }

    /** Band-local offset into the End band, or {@code -1} if {@code worldX} isn't in it. */
    private static long endBandOffset(Loc l) {
        if (l == null) return -1L;
        long le = l.local() - l.endStart();
        return (le < 0L || le >= l.endLen()) ? -1L : le;
    }

    /** Nether band-presence ramp (0 outside the nether segment) — drives in-band gating. */
    public double netherHeightRamp(int worldX) {
        Loc l = locate(worldX);
        long ln = netherBandOffset(l);
        if (ln < 0L) return 0.0;
        return NetherTransition.heightRamp((int) ln, 0L, riseLen(), megaHold, coreFade,
                scaledHold(coreHold, l.scale()), 0);
    }

    /** Nether intensity ramp (netherrack → real Nether) at a world-X (0 outside the nether segment). */
    public double netherRamp(int worldX) {
        Loc l = locate(worldX);
        long ln = netherBandOffset(l);
        if (ln < 0L) return 0.0;
        return NetherTransition.netherRamp((int) ln, 0L, riseLen(), megaHold, coreFade,
                scaledHold(coreHold, l.scale()), 0);
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
     * Heightmap multiplier at a world-X: 1 outside the nether segment, {@code 1} across stage 1, ramping
     * stage by stage to the final multiplier, then held at that multiplier across the mega plateau + the
     * (scaled) core, and mirrored on the exit. The rise/edge math is unaffected by the period scale —
     * only the held core in the middle grows longer.
     */
    public double netherMountainMultiplier(int worldX) {
        Loc l = locate(worldX);
        long ln = netherBandOffset(l);
        if (ln < 0L) return 1.0;
        long band = l.netherLen();
        int rise = riseLen();
        long edge = rise + Math.max(0, megaHold);
        if (ln < edge) return riseMult(ln, rise);
        if (ln >= band - edge) return riseMult(band - ln, rise);
        return stageMult(stageCount() - 1);                        // core region: last (mega) held
    }

    /**
     * Stage curve over the rise: the leading beach span holds the natural ×1 (the feature boosts it over
     * ocean), then each mountain stage ramps from the previous multiplier to its own (stage 1 ramps from
     * ×1), so the mountains grow smoothly stage by stage; the final multiplier is held across the mega
     * plateau.
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

    /**
     * Edge feather {@code 0..1} for the mountain raise, used to scale the above-sea mountain height:
     * {@code 0} exactly at the leading gate boundary (the inland edge of the beach span, where
     * {@link games.brennan.dungeontrain.worldgen.NetherMountainTerrain#raises} first turns on) and at
     * the symmetric trailing gate, smoothstep up to {@code 1} over one mountain stage on each side, and
     * {@code 1} across the whole interior. Scaling the added height by this makes it start at 0 at each
     * band edge, so the mountains grow out of the natural terrain instead of stepping up as a vertical
     * cliff. {@code 1} (a no-op) outside the nether segment or when the band has no stages.
     *
     * <p>The fade length is one mountain stage ({@code stageBlocks}); the feather therefore reaches
     * {@code 1} well before the netherrack crossfade/core begin, leaving them untouched. {@code min} of
     * the two edge distances keeps it symmetric and self-protecting if the band is too short to reach
     * full height. The trailing distance uses the <em>scaled</em> band length, so a longer core just
     * extends the {@code 1} interior. Pure (deterministic, seed-independent) like the rest of this class.</p>
     */
    public double netherMountainFeather(int worldX) {
        Loc l = locate(worldX);
        long ln = netherBandOffset(l);
        if (ln < 0L) return 1.0;                                    // outside the nether segment
        int fade = Math.max(0, stageBlocks);                        // ease in over the first stage
        if (fade == 0) return 1.0;
        long lead = ln - Math.max(0, beachBlocks);                 // blocks past the leading gate
        long trail = l.netherLen() - ln;                           // blocks to the trailing gate
        double e = Math.min(lead, trail) / (double) fade;
        if (e <= 0.0) return 0.0;
        if (e >= 1.0) return 1.0;
        return e * e * (3.0 - 2.0 * e);                            // smoothstep
    }

    /** True if {@code worldX} lies in the leading beach span of a nether band (the ocean-entry stretch). */
    public boolean isNetherBeachStage(int worldX) {
        Loc l = locate(worldX);
        if (l == null) return false;
        long ln = l.local() - l.netherStart();
        return ln >= 0 && ln < Math.max(0, beachBlocks);
    }

    /**
     * Progress {@code 0..1} across the leading nether beach span: {@code 0} at the seaward entrance edge
     * (the ocean waterline) climbing to {@code 1} at the inland edge where the beach meets the mountains
     * — so a shore ramp can be drawn across it. Clamped to {@code [0,1]}; outside the beach span the
     * value is meaningless, so callers must gate on {@link #isNetherBeachStage}.
     */
    public double netherBeachProgress(int worldX) {
        int beach = Math.max(0, beachBlocks);
        if (beach == 0) return 0.0;
        Loc l = locate(worldX);
        if (l == null) return 0.0;
        long ln = l.local() - l.netherStart();
        if (ln <= 0L) return 0.0;
        double p = (double) ln / beach;
        return p < 0.0 ? 0.0 : (p > 1.0 ? 1.0 : p);
    }

    /** World-X where the current period's nether band rise begins (for ocean detection), or {@code Long.MIN_VALUE}. */
    public long netherBandEntranceX(int worldX) {
        Loc l = locate(worldX);
        if (l == null) return Long.MIN_VALUE;
        long ln = l.local() - l.netherStart();
        return (long) worldX - ln;
    }

    /** End erosion / sky ramp at a world-X (0 outside the End segment). */
    public double endMiddleRamp(int worldX) {
        Loc l = locate(worldX);
        long le = endBandOffset(l);
        if (le < 0L) return 0.0;
        return Disintegration.middleRamp((int) le, 0L, eFade,
                scaledHold(eVoid, l.scale()), scaledHold(eEnd, l.scale()), 0);
    }

    /** End-island fill ramp at a world-X (0 outside the End segment). */
    public double endIslandRamp(int worldX) {
        Loc l = locate(worldX);
        long le = endBandOffset(l);
        if (le < 0L) return 0.0;
        return Disintegration.endRamp((int) le, 0L, eFade,
                scaledHold(eVoid, l.scale()), scaledHold(eEnd, l.scale()), 0);
    }

    /**
     * End sky/fog ramp at a world-X — like {@link #endMiddleRamp} but with both fades pushed
     * {@code skyOffset} blocks toward the void core, so the End sky lags the terrain erosion (delayed
     * fade-in on entry, early fade-out on exit). 0 outside the End segment; {@code skyOffset == 0}
     * reproduces {@link #endMiddleRamp} exactly.
     */
    public double endSkyRamp(int worldX, int skyOffset) {
        Loc l = locate(worldX);
        long le = endBandOffset(l);
        if (le < 0L) return 0.0;
        return Disintegration.skyRamp((int) le, 0L, eFade,
                scaledHold(eVoid, l.scale()), scaledHold(eEnd, l.scale()), 0, skyOffset);
    }
}
