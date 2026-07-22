package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;

/**
 * The single repeating world-gen cycle the train crosses, laying out ALL special
 * phases in one fixed order along +X from a shared anchor:
 *
 * <pre>
 *   OW → Nether transition → Nether → Nether transition → OW → Void → End islands → Void → Upside-down → exit-fade → OW → Chuncks → OW (repeat)
 * </pre>
 *
 * i.e. per period: {@code [owGap] [nether band] [owGap] [end band] [upside-down band] [udExitFade] [udExitGap] [chuncks band]}. The
 * nether/End sub-bands reuse the existing ramp math ({@link NetherTransition} and
 * {@link Disintegration}) evaluated at a <em>local</em> offset with {@code owHold = 0}; the
 * upside-down band uses a simple trapezoid ({@link #upsideDownRamp}) and is realised as a
 * post-process vertical mirror (see {@code WorldUpsideDownEvents}). This class only owns the
 * layout/positioning. The upside-down band flows directly out of the End band (no overworld gap
 * between them) and is followed by its own trailing {@code udExitGap} of plain overworld before the
 * cycle's leading {@code owGap} resumes; both are present only when the band has length, so
 * {@link #period()} is byte-identical to the pre-existing two-band cycle when it is off.
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
 * @param udFade     upside-down atmosphere fade span (each side); 0 disables the band
 * @param udHold     upside-down mirrored-world core span
 * @param udExit     plain-overworld gap inserted after the upside-down band, before the cycle's
 *                   leading {@code owGap} resumes; 0 when the band is disabled
 * @param udExitFade upside-down → overworld exit crossfade span, inserted between the band and the
 *                   trailing {@code udExit} gap: the mirror disperses into shrinking floating islands
 *                   while overworld islands fade in over the void. 0 = hard edge (period unchanged)
 * @param chuncksHold length of the "chuncks" band — a mostly-void stretch, sprinkled with occasional
 *                   real overworld chunks (some vertically complete, some a top-down slice). Appended
 *                   after the upside-down band's trailing {@code udExit} gap. 0 disables the band
 *                   (period byte-identical to the pre-chuncks cycle)
 * @param chuncksFade length of the entry fade zone before the band: the keep-density ramps from 1
 *                   (all real terrain, no void) down to {@code chuncksKeepDensity} across it, so void
 *                   chunks become progressively more common on approach. 0 = hard edge
 * @param chuncksLeadGap plain-overworld gap inserted before the chuncks band (after the upside-down
 *                   band's own exit gap), between the upside-down exit fade and the chuncks entry fade.
 *                   Breathing room so the two special zones don't run together. 0 = none
 * @param chuncksKeepDensity fraction {@code 0..1} of chunks in the chuncks band that keep real terrain
 *                   (the rest are void); a per-chunk seed-stable noise gate. 0 = all void
 * @param chuncksSliceRatio fraction {@code 0..1} of the KEPT chunks that are a top-down slice (surface
 *                   kept, flat bottom cut) rather than vertically complete
 * @param phaseShift blocks the whole cycle is shifted at {@code startX} so the FIRST overworld gap
 *                   (to the nether band) is shorter than the recurring {@code owGap}; {@code
 *                   max(0, owGap − firstOverworld)}, 0 = no shift. Shared with the End band's
 *                   disintegration phase-shift so both layouts stay in lock-step.
 */
public record WorldGenCycle(long startX, int owGap,
                            int stageBlocks, int[] stageMultipliers, int beachBlocks, int megaHold,
                            int coreFade, int coreHold,
                            int eFade, int eVoid, int eEnd,
                            int udFade, int udHold, int udExit, int udExitFade,
                            int chuncksHold, int chuncksFade, int chuncksLeadGap,
                            double chuncksKeepDensity, double chuncksSliceRatio,
                            int phaseShift) {

    /**
     * Back-compat constructor for the pre-chuncks 16-arg shape (with {@code udExitFade}, no chuncks
     * band). Passes {@code chuncksHold = 0} so {@link #period()} is byte-identical to the pre-chuncks
     * cycle — existing callers and unit tests keep the old layout unchanged.
     */
    public WorldGenCycle(long startX, int owGap,
                         int stageBlocks, int[] stageMultipliers, int beachBlocks, int megaHold,
                         int coreFade, int coreHold,
                         int eFade, int eVoid, int eEnd,
                         int udFade, int udHold, int udExit, int udExitFade, int phaseShift) {
        this(startX, owGap, stageBlocks, stageMultipliers, beachBlocks, megaHold, coreFade, coreHold,
                eFade, eVoid, eEnd, udFade, udHold, udExit, udExitFade, 0, 0, 0, 0.0, 0.0, phaseShift);
    }

    /**
     * Back-compat constructor defaulting {@code udExitFade} (the upside-down → overworld exit crossfade)
     * to 0 — the pre-exit-fade 15-arg shape (also chuncks-free). A zero exit fade is byte-identical to
     * the previous cycle, so existing callers and unit tests keep the old layout unchanged.
     */
    public WorldGenCycle(long startX, int owGap,
                         int stageBlocks, int[] stageMultipliers, int beachBlocks, int megaHold,
                         int coreFade, int coreHold,
                         int eFade, int eVoid, int eEnd,
                         int udFade, int udHold, int udExit, int phaseShift) {
        this(startX, owGap, stageBlocks, stageMultipliers, beachBlocks, megaHold, coreFade, coreHold,
                eFade, eVoid, eEnd, udFade, udHold, udExit, 0, phaseShift);
    }

    /**
     * Memoised {@link #build} result. {@code fromConfig} is a pure read of the GLOBAL COMMON
     * config, so a single cached instance is valid until the config reloads. It is invalidated by
     * {@link #invalidateCache()} on every COMMON {@code ModConfigEvent} (Loading + Reloading), wired
     * from {@code DungeonTrain}. {@code volatile} so a worldgen thread always sees the latest
     * instance (or {@code null} after an invalidation); the record is immutable and never mutates
     * its {@code stageMultipliers} array, so sharing one instance across threads is safe.
     */
    private static volatile WorldGenCycle cached;

    /**
     * Live COMMON-config cycle, memoised. Before this cache, the band classifiers
     * ({@link NetherBand}, {@link DisintegrationBand}, the nether/biome features) rebuilt this
     * record — ~14 config reads + a {@code getNetherStageMultipliers()} string-parse + allocs —
     * on every per-column call (measured at millions/play-session). Now it builds once per config
     * (re)load. Double-checked locking: the {@code volatile} read is the warm fast path; the
     * {@code synchronized} block runs only on the first call and immediately after an invalidation.
     * Byte-identical to the un-cached value (same global config in, same record out).
     */
    public static WorldGenCycle fromConfig() {
        WorldGenCycle c = cached;
        if (c != null) return c;
        synchronized (WorldGenCycle.class) {
            if (cached == null) cached = build();
            return cached;
        }
    }

    /**
     * Drop the memoised {@link #fromConfig} cycle so the next call rebuilds from current config.
     * Called from the COMMON {@code ModConfigEvent} listener (Loading clears any pre-load default
     * cycle; Reloading covers config-screen / file-watcher edits). Pure (no Minecraft types) so the
     * record stays unit-testable.
     */
    public static void invalidateCache() {
        cached = null;
    }

    /** Build from live COMMON config; a disabled phase collapses to zero length (just overworld). */
    private static WorldGenCycle build() {
        boolean nether = DungeonTrainCommonConfig.isNetherTransitionEnabled();
        boolean end = DungeonTrainCommonConfig.isDisintegrationEnabled();
        boolean ud = DungeonTrainCommonConfig.isUpsideDownEnabled();
        boolean chuncks = DungeonTrainCommonConfig.isChuncksEnabled();
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
                ud ? DungeonTrainCommonConfig.getUpsideDownFadeBlocks() : 0,
                ud ? DungeonTrainCommonConfig.getUpsideDownHoldBlocks() : 0,
                ud ? DungeonTrainCommonConfig.getUpsideDownExitGapBlocks() : 0,
                ud ? DungeonTrainCommonConfig.getUpsideDownExitFadeBlocks() : 0,
                chuncks ? DungeonTrainCommonConfig.getChuncksHoldBlocks() : 0,
                chuncks ? DungeonTrainCommonConfig.getChuncksFadeBlocks() : 0,
                chuncks ? DungeonTrainCommonConfig.getChuncksLeadGapBlocks() : 0,
                chuncks ? DungeonTrainCommonConfig.getChuncksKeepDensity() : 0.0,
                chuncks ? DungeonTrainCommonConfig.getChuncksSliceRatio() : 0.0,
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

    /** Combined length of the upside-down band ({@code 2·udFade + udHold}); 0 when the band is disabled. */
    public long upsideDownLen() {
        return 2L * Math.max(0, udFade) + Math.max(0, udHold);
    }

    /**
     * Length of the upside-down → overworld exit crossfade, inserted between the band and the trailing
     * {@code udExit} gap. Gated on {@code upsideDownLen > 0} (like {@link #udExitGap()}) so a disabled
     * band — or a zero {@code udExitFade} — keeps {@link #period()} byte-identical to the pre-existing
     * cycle, protecting existing world layouts and the cycle unit tests.
     */
    public long udExitFadeLen() {
        return upsideDownLen() > 0L ? Math.max(0, udExitFade) : 0L;
    }

    /**
     * The upside-down band's own trailing overworld gap — present only when the band has length. Gating
     * it on {@code upsideDownLen > 0} keeps {@link #period()} byte-identical to the two-band cycle when
     * the band is disabled, protecting existing world layouts and the cycle unit tests.
     */
    private long udExitGap() {
        return upsideDownLen() > 0L ? Math.max(0, udExit) : 0L;
    }

    /** Length of the chuncks band core (the full-density {@code chuncksHold}); 0 when disabled. */
    public long chuncksLen() {
        return Math.max(0, chuncksHold);
    }

    /**
     * Length of the chuncks entry fade zone before the band core; gated on {@code chuncksLen > 0} so a
     * disabled band — or a zero {@code chuncksFade} — keeps {@link #period()} byte-identical.
     */
    public long chuncksFadeLen() {
        return chuncksLen() > 0L ? Math.max(0, chuncksFade) : 0L;
    }

    /**
     * Plain-overworld gap before the chuncks band (after the upside-down exit gap); gated on
     * {@code chuncksLen > 0} so it collapses to 0 — and keeps {@link #period()} byte-identical — when
     * the band is disabled.
     */
    public long chuncksLeadGapLen() {
        return chuncksLen() > 0L ? Math.max(0, chuncksLeadGap) : 0L;
    }

    /** {@code 2·owGap + netherLen + endLen + udLen + udExitFade + udExitGap + chuncksLeadGap + chuncksFade + chuncksLen}. */
    public long period() {
        return 2L * Math.max(0, owGap) + netherLen() + endLen()
                + upsideDownLen() + udExitFadeLen() + udExitGap()
                + chuncksLeadGapLen() + chuncksFadeLen() + chuncksLen();
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

    /** Offset (into the cycle) where the upside-down band begins — immediately after the End band, no gap. */
    private long udStart() {
        return 2L * Math.max(0, owGap) + netherLen() + endLen();
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

    /**
     * Edge feather {@code 0..1} for the mountain raise, used to scale the above-sea mountain height:
     * {@code 0} exactly at the leading gate boundary (the inland edge of the beach span, where
     * {@link games.brennan.dungeontrain.worldgen.NetherMountainTerrain#raises} first turns on) and at
     * the symmetric trailing gate, smoothstep up to {@code 1} over one mountain stage on each side,
     * and {@code 1} across the whole interior. Scaling the added height by this makes it start at 0 at
     * each band edge, so the mountains grow out of the natural terrain instead of stepping up as a
     * vertical cliff. {@code 1} (a no-op) outside the nether segment or when the band has no stages.
     *
     * <p>The fade length is one mountain stage ({@code stageBlocks}); the feather therefore reaches
     * {@code 1} well before the netherrack crossfade/core begin, leaving them untouched. {@code min}
     * of the two edge distances keeps it symmetric and self-protecting if the band is too short to
     * reach full height. Pure (deterministic, seed-independent) like the rest of this class.</p>
     */
    public double netherMountainFeather(int worldX) {
        long ln = netherOffset(worldX);
        if (ln < 0L) return 1.0;                                    // outside the nether segment
        int fade = Math.max(0, stageBlocks);                        // ease in over the first stage
        if (fade == 0) return 1.0;
        long lead = ln - Math.max(0, beachBlocks);                 // blocks past the leading gate
        long trail = netherLen() - ln;                             // blocks to the trailing gate
        double e = Math.min(lead, trail) / (double) fade;
        if (e <= 0.0) return 0.0;
        if (e >= 1.0) return 1.0;
        return e * e * (3.0 - 2.0 * e);                            // smoothstep
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

    /**
     * True iff ANY x′ in the inclusive window {@code [worldX − margin, worldX + margin]} falls inside
     * the nether segment of some cycle repeat — i.e. {@code netherHeightRamp}/{@code netherRamp} could
     * be non-zero there. The margin absorbs the {@link NetherMountainTerrain#wavyX} edge wave (pass
     * {@link NetherMountainTerrain#maxEdgeShift()}), so a {@code false} here guarantees every waved
     * lookup a caller could make at {@code worldX} lands in plain overworld.
     *
     * <p>An O(1) circular-interval intersection — no per-x′ sweep — so hot worldgen paths (the density
     * raise, the biome-forcing mixin) can early-out for the ride's majority off-band columns.
     * <b>Conservative by construction</b>: window endpoints only ever widen (the window is clamped to
     * the anchor, a window ≥ one period is always {@code true}), so false positives merely skip the
     * optimisation; a false negative is impossible — the seam-safety property the stride-1 unit sweep
     * pins.</p>
     */
    public boolean netherInfluence(long worldX, int margin) {
        return segmentInfluence(worldX, margin, netherStart(), netherLen());
    }

    /**
     * True iff {@code worldX} falls inside the End segment of some cycle repeat — plain membership, no
     * margin (the End band is evaluated at the un-waved X everywhere: {@code endMiddleRamp},
     * {@code endIslandRamp}, {@code isEndCore}). A {@code false} guarantees all End ramps are 0 at
     * {@code worldX}, letting hot paths skip them entirely. Same conservative O(1) contract as
     * {@link #netherInfluence}.
     */
    public boolean endSegmentInfluence(long worldX) {
        return segmentInfluence(worldX, 0, endStart(), endLen());
    }

    /**
     * Shared circular-window intersection behind {@link #netherInfluence}/{@link #endSegmentInfluence}:
     * does the inclusive window {@code [worldX − margin, worldX + margin]}, clamped to the anchor and
     * folded into cycle offsets, intersect the segment {@code [segStart, segStart + segLen)}? Two
     * circular arcs intersect iff either contains the other's start point — both checks below via
     * {@code floorMod} forward distances, exact for any window/segment phase including period wrap.
     */
    private boolean segmentInfluence(long worldX, int margin, long segStart, long segLen) {
        if (segLen <= 0L) return false;                       // segment disabled → never influences
        long p = period();
        if (p <= 0L) return false;
        long m = Math.max(0, margin);
        long b = worldX + m;                                  // inclusive window [a, b]
        if (b < startX) return false;                         // wholly before the anchor → plain overworld
        long a = Math.max(worldX - m, startX);                // clamp: offsets are undefined pre-anchor
        long w = b - a;                                       // inclusive window span
        if (w >= p) return true;                              // covers a full period → hits every segment
        long oa = Math.floorMod(a - startX + phaseShift, p);  // window start as a cycle offset
        if (Math.floorMod(segStart - oa, p) <= w) return true; // segment start inside the window
        return Math.floorMod(oa - segStart, p) < segLen;      // window start inside the segment
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
     * End-core ramp at/above which a column is the real-End <b>core</b> (full island fill, not the
     * void/fade edges). Shared by the biome-source mixin, which tags core columns with a real End
     * biome sampled via {@link games.brennan.dungeontrain.worldgen.density.EndCoreBiomes}.
     */
    public static final double END_CORE_THRESHOLD = 0.999;

    /** True if {@code worldX} is a real-End core column ({@link #endIslandRamp} ≥ {@link #END_CORE_THRESHOLD}). */
    public boolean isEndCore(int worldX) {
        return endIslandRamp(worldX) >= END_CORE_THRESHOLD;
    }

    /**
     * Which repeat of the world-gen cycle {@code worldX} falls in (0-based), or {@code -1} before the
     * anchor / when the cycle is empty. The general form behind {@link #endPassIndex}: because the Nether
     * band is the first special band of every period, this doubles as the Nether-band pass index (repeat
     * 0 = first Nether band, ≥ 1 = second onward), which the "Nether Return Again" advancement keys off
     * via {@link games.brennan.dungeontrain.worldgen.NetherBand#netherPassIndex}.
     */
    public long cycleIndex(int worldX) {
        long p = period();
        if (p <= 0L || worldX < startX) return -1L;
        return Math.floorDiv((long) worldX - startX + phaseShift, p);
    }

    /**
     * Which repeat of the world-gen cycle this world-X falls in (0-based), or {@code -1} before the
     * anchor / when the cycle is empty. Drives {@link games.brennan.dungeontrain.worldgen.density.EndCoreBiomes}'s
     * sweep from the real End's main island (pass 0) out into its outer noise field (later passes), so
     * a normal game session's handful of End-band crossings covers all five real End biomes instead of
     * repeatedly sampling the same spot. Alias of {@link #cycleIndex} — the End band sits in every cycle
     * repeat, so its pass index is the cycle index.
     */
    public long endPassIndex(int worldX) {
        return cycleIndex(worldX);
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

    /** Offset into the upside-down band at a world-X, or {@code -1} outside it. */
    private long udOffset(int worldX) {
        long o = offset(worldX);
        if (o < 0L) return -1L;
        long lu = o - udStart();
        return (lu < 0L || lu >= upsideDownLen()) ? -1L : lu;
    }

    /**
     * Upside-down atmosphere ramp {@code 0..1} at a world-X: 0 outside the band, ramping 0→1 over
     * {@code udFade}, held at 1 across the core, then 1→0 over {@code udFade}. Drives the client
     * sky/light crossfade and the mob-spawn gate. Pure (seed-independent), like the other ramps.
     */
    public double upsideDownRamp(int worldX) {
        long lu = udOffset(worldX);
        if (lu >= 0L) {                                        // inside the band core + edge fades
            int fade = Math.max(0, udFade);
            if (fade == 0) return 1.0;
            long band = upsideDownLen();
            if (lu < fade) return (double) lu / fade;          // leading fade-in
            long holdEnd = band - fade;
            if (lu < holdEnd) return 1.0;                      // core hold
            if (udExitFadeLen() > 0L) return 1.0;              // exit crossfade present → hold at 1, it carries the fade-out
            return Math.max(0.0, (double) (band - lu) / fade); // trailing fade-out (byte-identical when no exit fade)
        }
        long ex = udExitFadeOffset(worldX);                    // exit crossfade: sky/light fades 1→0 across the whole zone
        if (ex >= 0L) {
            long len = udExitFadeLen();
            return len > 0L ? Math.max(0.0, (double) (len - ex) / len) : 0.0;
        }
        return 0.0;
    }

    /**
     * True if {@code worldX} lies anywhere in the upside-down band (fade edges included). The terrain
     * mirror is all-or-nothing per column, so this binary membership — not {@link #upsideDownRamp} —
     * gates the reflection; the ramp only crossfades the client atmosphere.
     */
    public boolean isInUpsideDownBand(int worldX) {
        return udOffset(worldX) >= 0L;
    }

    /**
     * Length of the entry lead-in zone immediately before {@code udStart} — {@code udFade} clamped to
     * {@code eVoid} so it never reaches past the trailing void hold into the End core. 0 when the band
     * is disabled or the void hold has no length.
     */
    public long udEntryLeadLen() {
        return Math.min(Math.max(0, udFade), Math.max(0, eVoid));
    }

    /** Offset (into the cycle) where the entry lead-in zone begins — {@code udEntryLeadLen} before {@code udStart}. */
    private long udEntryLeadStart() {
        return udStart() - udEntryLeadLen();
    }

    /**
     * True if {@code worldX} lies in the entry lead-in zone {@code [udEntryLeadStart, udStart)} —
     * immediately before the upside-down band, inside the End band's trailing void hold. Disjoint from
     * {@link #isInUpsideDownBand}: a column is in at most one of the two.
     */
    public boolean isInUpsideDownEntryLead(int worldX) {
        long o = offset(worldX);
        if (o < 0L) return false;
        long lead = udEntryLeadLen();
        if (lead <= 0L) return false;
        long l = o - udEntryLeadStart();
        return l >= 0L && l < lead;
    }

    /**
     * Reveal ramp {@code 0..1} across the entry lead-in zone: 0 at {@code udEntryLeadStart} (start of
     * the void-hold approach), linear up to 1 at {@code udStart} (where the true band's full mirror
     * takes over). 0 outside the zone. Drives the noise-gated partial terrain mirror in
     * {@code WorldUpsideDownEvents} — the terrain analogue of {@link #upsideDownRamp}'s atmosphere fade.
     */
    public double upsideDownEntryRevealRamp(int worldX) {
        if (!isInUpsideDownEntryLead(worldX)) return 0.0;
        long lead = udEntryLeadLen();
        if (lead <= 0L) return 0.0;
        long l = offset(worldX) - udEntryLeadStart();
        return (double) l / lead;
    }

    /**
     * True if {@code worldX} lies in the upside-down band OR its entry lead-in zone — the full stretch
     * where {@code WorldUpsideDownEvents} produces mirrored (visually inverted) terrain and the client
     * render-flip / water-freeze / flipped-corridor apply. The band is the solid core; the lead-in is
     * the Y-windowed reveal running up to it. Combined so those consumers treat both alike.
     */
    public boolean isInUpsideDownBandOrEntryLead(int worldX) {
        return isInUpsideDownBand(worldX) || isInUpsideDownEntryLead(worldX);
    }

    /** Offset (into the cycle) where the exit crossfade begins — immediately after the upside-down band. */
    private long udExitFadeStart() {
        return udStart() + upsideDownLen();
    }

    /** Offset into the exit crossfade at a world-X, or {@code -1} outside it. */
    private long udExitFadeOffset(int worldX) {
        long o = offset(worldX);
        if (o < 0L) return -1L;
        long l = o - udExitFadeStart();
        return (l < 0L || l >= udExitFadeLen()) ? -1L : l;
    }

    /**
     * True if {@code worldX} lies in the upside-down → overworld exit crossfade — the zone immediately
     * after the band where the mirror disperses and overworld islands fade in. Disjoint from
     * {@link #isInUpsideDownBand} and {@link #isInUpsideDownEntryLead}: a column is in at most one.
     */
    public boolean isInUpsideDownExitFade(int worldX) {
        return udExitFadeOffset(worldX) >= 0L;
    }

    /**
     * Overworld-reveal ramp {@code 0..1} across the exit crossfade: {@code 0} at the band's trailing
     * edge (full mirror, no overworld yet) climbing to {@code 1} at the zone end (solid overworld). 0
     * outside the zone. Drives how much of the normal terrain is un-eroded back in
     * ({@code WorldUpsideDownEvents}). Pure (seed-independent).
     */
    public double upsideDownExitOwRevealRamp(int worldX) {
        long l = udExitFadeOffset(worldX);
        if (l < 0L) return 0.0;
        long len = udExitFadeLen();
        return len > 0L ? (double) l / len : 0.0;
    }

    /**
     * Mirror-disperse ramp {@code 1..0} across the exit crossfade: {@code 1} at the band's trailing edge
     * (full mirror, continuous with the band) falling to {@code 0} at the zone end (mirror gone). 0
     * outside the zone. As it falls the surviving mirror islands shrink and spread apart. Pure
     * (seed-independent); the linear complement of {@link #upsideDownExitOwRevealRamp} today, kept
     * separate so the two can be staggered later.
     */
    public double upsideDownExitMirrorDisperseRamp(int worldX) {
        long l = udExitFadeOffset(worldX);
        if (l < 0L) return 0.0;
        long len = udExitFadeLen();
        return len > 0L ? (double) (len - l) / len : 0.0;
    }

    /**
     * Offset (into the cycle) where the chuncks entry fade zone begins — after the upside-down band's
     * trailing overworld gap ({@code udExitGap}) and the chuncks lead-in gap ({@code chuncksLeadGap}).
     * When the upside-down band is disabled all its spans are 0, so this collapses to right after the End
     * band (plus the lead gap); the chuncks band's placement is independent of whether upside-down is present.
     */
    private long chuncksFadeStart() {
        return udStart() + upsideDownLen() + udExitFadeLen() + udExitGap() + chuncksLeadGapLen();
    }

    /** Offset where the full-density chuncks core begins — after the entry fade zone. */
    private long chuncksStart() {
        return chuncksFadeStart() + chuncksFadeLen();
    }

    /** Offset into the chuncks band core at a world-X, or {@code -1} outside it. */
    private long chuncksOffset(int worldX) {
        long o = offset(worldX);
        if (o < 0L) return -1L;
        long lc = o - chuncksStart();
        return (lc < 0L || lc >= chuncksLen()) ? -1L : lc;
    }

    /**
     * True if {@code worldX} lies in the full-density chuncks band core (not the entry fade). Membership
     * is binary (per-column) like {@link #isInUpsideDownBand}; the per-<em>chunk</em> void/keep/slice
     * decision is a seed-stable noise gate applied on top of the {@link #chuncksKeepDensityAt density}
     * (see {@code ChuncksBand}), not part of the pure layout.
     */
    public boolean isInChuncksBand(int worldX) {
        return chuncksOffset(worldX) >= 0L;
    }

    /**
     * Effective keep-density at a world-X, driving the entry transition: {@code chuncksKeepDensity}
     * across the band core, ramping linearly from {@code 1.0} (all real terrain, no void) at the entry
     * fade start up to {@code chuncksKeepDensity} at the core edge, and {@code 1.0} everywhere else (so
     * chunks outside the band + fade are always kept). Pure (seed-independent), like the other ramps.
     */
    public double chuncksKeepDensityAt(int worldX) {
        if (chuncksLen() <= 0L) return 1.0;                         // band disabled → all real terrain
        long o = offset(worldX);
        if (o < 0L) return 1.0;
        long holdStart = chuncksStart();
        if (o >= holdStart && o < holdStart + chuncksLen()) return chuncksKeepDensity;  // full-density core
        long fadeLen = chuncksFadeLen();
        if (fadeLen > 0L) {
            long fadeStart = holdStart - fadeLen;
            if (o >= fadeStart && o < holdStart) {
                double t = (double) (o - fadeStart) / fadeLen;      // 0 at fade start → 1 at core edge
                return 1.0 + (chuncksKeepDensity - 1.0) * t;        // lerp 1 → keepDensity
            }
        }
        return 1.0;                                                 // outside the band + fade
    }
}
