package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.worldgen.density.BetterNetherCoreBiomes;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandConfig;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;

import java.util.ArrayList;
import java.util.List;

/**
 * The single repeating world-gen cycle the train crosses, laying out ALL special
 * phases in one fixed order along +X from a shared anchor:
 *
 * <pre>
 *   OW → Nether transition → Nether → Nether transition → OW → Void → End islands → Void → Upside-down → exit-fade → OW → Chuncks → OW → Spheres → OW → Stacks → (OW → legacy band)… → (repeat)
 * </pre>
 *
 * i.e. per period: {@code [owGap] [nether band] [owGap] [end band] [upside-down band] [udExitFade] [udExitGap] [chuncks band] [spheresLeadGap] [spheresFade] [spheres band] [stacksLeadGap] [stacksFade] [stacks band] ([legacyLeadGap] [legacyFade] [legacy band] [legacyFade])…}. The
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
 * @param spheresHold length of the "spheres" band core — open void scattered with floating spheres of
 *                   natural overworld terrain, each lifted to its own height (see {@code SphereField} /
 *                   {@code WorldSpheresEvents}). Appended after the chuncks band. 0 disables the band
 *                   (period byte-identical to the pre-spheres cycle)
 * @param spheresFade length of the entry fade zone before the band: the natural terrain outside the
 *                   spheres dissolves into void across it (void ramp 0 → 1). 0 = hard edge
 * @param spheresLeadGap plain-overworld gap inserted before the spheres band (after the chuncks core),
 *                   before the spheres entry fade. 0 = none
 * @param stacksHold length of the "stacks" band — a mostly-void stretch where scattered chunks each hold
 *                   a vertical stack of one vanilla structure piece repeated from the world floor to near
 *                   build height. Appended after the spheres band (plus its own lead gap + fade). 0 disables
 *                   the band (period byte-identical to the pre-stacks cycle)
 * @param stacksFade length of the entry fade zone before the band: the fraction of chunks turned to void
 *                   ramps from 0 (all real terrain) to 1 (all void) across it. 0 = hard edge
 * @param stacksLeadGap plain-overworld gap inserted between the end of the spheres band and the stacks
 *                   entry fade. 0 = none
 * @param stacksDensity fraction {@code 0..1} of the band's void chunks that hold a stack (the rest are
 *                   empty void); a per-chunk seed-stable noise gate
 * @param legacy     legacy bands appended after the stacks band, in {@link LegacyBandKind} (cycle)
 *                   order — each {@code [leadGap] [fade] [hold] [fade]} of terrain from an old Minecraft
 *                   generator (see {@link LegacySpan}). Disabled spans have zero length; an empty array
 *                   keeps {@link #period()} byte-identical to the pre-legacy cycle. Never mutated.
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
                            int spheresHold, int spheresFade, int spheresLeadGap,
                            int stacksHold, int stacksFade, int stacksLeadGap, double stacksDensity,
                            LegacySpan[] legacy,
                            CycleLayout layout,
                            int phaseShift) {

    /**
     * Back-compat constructor for the classic single-period shape (every band once, in the fixed
     * order, legacy spans with their own lead gaps): no {@link CycleLayout}, so every helper takes its
     * classic chained-offset branch and {@link #period()} is the classic sum.
     */
    public WorldGenCycle(long startX, int owGap,
                         int stageBlocks, int[] stageMultipliers, int beachBlocks, int megaHold,
                         int coreFade, int coreHold,
                         int eFade, int eVoid, int eEnd,
                         int udFade, int udHold, int udExit, int udExitFade,
                         int chuncksHold, int chuncksFade, int chuncksLeadGap,
                         double chuncksKeepDensity, double chuncksSliceRatio,
                         int spheresHold, int spheresFade, int spheresLeadGap,
                         int stacksHold, int stacksFade, int stacksLeadGap, double stacksDensity,
                         LegacySpan[] legacy,
                         int phaseShift) {
        this(startX, owGap, stageBlocks, stageMultipliers, beachBlocks, megaHold, coreFade, coreHold,
                eFade, eVoid, eEnd, udFade, udHold, udExit, udExitFade,
                chuncksHold, chuncksFade, chuncksLeadGap, chuncksKeepDensity, chuncksSliceRatio,
                spheresHold, spheresFade, spheresLeadGap,
                stacksHold, stacksFade, stacksLeadGap, stacksDensity,
                legacy, null, phaseShift);
    }

    /**
     * Back-compat constructor for the pre-legacy 28-arg shape (every band through stacks, no legacy
     * bands). Passes no legacy spans so {@link #period()} is byte-identical to the pre-legacy cycle.
     */
    public WorldGenCycle(long startX, int owGap,
                         int stageBlocks, int[] stageMultipliers, int beachBlocks, int megaHold,
                         int coreFade, int coreHold,
                         int eFade, int eVoid, int eEnd,
                         int udFade, int udHold, int udExit, int udExitFade,
                         int chuncksHold, int chuncksFade, int chuncksLeadGap,
                         double chuncksKeepDensity, double chuncksSliceRatio,
                         int spheresHold, int spheresFade, int spheresLeadGap,
                         int stacksHold, int stacksFade, int stacksLeadGap, double stacksDensity,
                         int phaseShift) {
        this(startX, owGap, stageBlocks, stageMultipliers, beachBlocks, megaHold, coreFade, coreHold,
                eFade, eVoid, eEnd, udFade, udHold, udExit, udExitFade,
                chuncksHold, chuncksFade, chuncksLeadGap, chuncksKeepDensity, chuncksSliceRatio,
                spheresHold, spheresFade, spheresLeadGap,
                stacksHold, stacksFade, stacksLeadGap, stacksDensity,
                NO_LEGACY, phaseShift);
    }

    /**
     * Back-compat constructor for the pre-stacks 24-arg shape (chuncks + spheres bands present, no stacks
     * band). Passes {@code stacksHold = 0} so {@link #period()} is byte-identical to the pre-stacks cycle —
     * existing callers and unit tests keep the old layout unchanged.
     */
    public WorldGenCycle(long startX, int owGap,
                         int stageBlocks, int[] stageMultipliers, int beachBlocks, int megaHold,
                         int coreFade, int coreHold,
                         int eFade, int eVoid, int eEnd,
                         int udFade, int udHold, int udExit, int udExitFade,
                         int chuncksHold, int chuncksFade, int chuncksLeadGap,
                         double chuncksKeepDensity, double chuncksSliceRatio,
                         int spheresHold, int spheresFade, int spheresLeadGap,
                         int phaseShift) {
        this(startX, owGap, stageBlocks, stageMultipliers, beachBlocks, megaHold, coreFade, coreHold,
                eFade, eVoid, eEnd, udFade, udHold, udExit, udExitFade,
                chuncksHold, chuncksFade, chuncksLeadGap, chuncksKeepDensity, chuncksSliceRatio,
                spheresHold, spheresFade, spheresLeadGap,
                0, 0, 0, 0.0, phaseShift);
    }

    /**
     * Back-compat constructor for the pre-spheres 21-arg shape (chuncks band present, no spheres
     * band). Passes {@code spheresHold = 0} so {@link #period()} is byte-identical to the pre-spheres
     * cycle — existing callers and unit tests keep the old layout unchanged.
     */
    public WorldGenCycle(long startX, int owGap,
                         int stageBlocks, int[] stageMultipliers, int beachBlocks, int megaHold,
                         int coreFade, int coreHold,
                         int eFade, int eVoid, int eEnd,
                         int udFade, int udHold, int udExit, int udExitFade,
                         int chuncksHold, int chuncksFade, int chuncksLeadGap,
                         double chuncksKeepDensity, double chuncksSliceRatio,
                         int phaseShift) {
        this(startX, owGap, stageBlocks, stageMultipliers, beachBlocks, megaHold, coreFade, coreHold,
                eFade, eVoid, eEnd, udFade, udHold, udExit, udExitFade,
                chuncksHold, chuncksFade, chuncksLeadGap, chuncksKeepDensity, chuncksSliceRatio,
                0, 0, 0, 0, 0, 0, 0.0, phaseShift);
    }

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
        boolean spheres = DungeonTrainCommonConfig.isSpheresEnabled();
        boolean stacks = DungeonTrainCommonConfig.isStacksEnabled();
        LegacySpan[] legacySpans = LegacyBandConfig.spans();
        int riseLen = nether ? Math.max(0, DungeonTrainCommonConfig.getNetherBeachBlocks())
                + DungeonTrainCommonConfig.getNetherStageMultipliers().length * Math.max(0, DungeonTrainCommonConfig.getNetherStageBlocks()) : 0;
        CycleLayout.Fades fades = new CycleLayout.Fades(
                riseLen,
                nether ? DungeonTrainCommonConfig.getNetherMountainHoldBlocks() : 0,
                nether ? DungeonTrainCommonConfig.getNetherCoreFadeBlocks() : 0,
                end ? DungeonTrainCommonConfig.getDisintegrationFadeBlocks() : 0,
                end ? DungeonTrainCommonConfig.getDisintegrationVoidHoldBlocks() : 0,
                ud ? DungeonTrainCommonConfig.getUpsideDownFadeBlocks() : 0,
                ud ? DungeonTrainCommonConfig.getUpsideDownExitGapBlocks() : 0,
                ud ? DungeonTrainCommonConfig.getUpsideDownExitFadeBlocks() : 0,
                chuncks ? DungeonTrainCommonConfig.getChuncksFadeBlocks() : 0,
                spheres ? DungeonTrainCommonConfig.getSpheresFadeBlocks() : 0,
                stacks ? DungeonTrainCommonConfig.getStacksFadeBlocks() : 0,
                legacyFadeOf(legacySpans));
        CycleLayout layout = CycleLayout.parse(DungeonTrainCommonConfig.getWorldgenCycleOrder(), fades, legacySpans,
                t -> switch (t) {
                    case OVERWORLD, LEGACY_RUN -> true;
                    case NETHER -> nether;
                    case END -> end;
                    case UPSIDE_DOWN -> ud;
                    case CHUNCKS -> chuncks;
                    case SPHERES -> spheres;
                    case STACKS -> stacks;
                },
                msg -> LOGGER.warn("[DungeonTrain] worldgenCycleOrder: {}", msg));
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
                spheres ? DungeonTrainCommonConfig.getSpheresHoldBlocks() : 0,
                spheres ? DungeonTrainCommonConfig.getSpheresFadeBlocks() : 0,
                spheres ? DungeonTrainCommonConfig.getSpheresLeadGapBlocks() : 0,
                stacks ? DungeonTrainCommonConfig.getStacksHoldBlocks() : 0,
                stacks ? DungeonTrainCommonConfig.getStacksFadeBlocks() : 0,
                stacks ? DungeonTrainCommonConfig.getStacksLeadGapBlocks() : 0,
                stacks ? DungeonTrainCommonConfig.getStacksDensity() : 0.0,
                legacySpans,
                layout,
                DungeonTrainCommonConfig.getDisintegrationPhaseShiftBlocks());
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(WorldGenCycle.class);

    /** The legacy eras' shared crossfade: the first enabled span's fade (480 by default). */
    private static int legacyFadeOf(LegacySpan[] spans) {
        for (LegacySpan sp : spans) {
            if (sp.holdLen() > 0L) return sp.fade();
        }
        return 480;
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
        return NetherTransition.bandLength(riseLen(), megaHold, coreFade, netherCoreOf(0));
    }

    /** Core length of the {@code i}-th Nether occurrence of a run (classic: the single {@code coreHold}); 0 if none. */
    private int netherCoreOf(int i) {
        if (layout == null) return coreHold;
        int seen = 0;
        for (int j = 0; j < layout.count(); j++) {
            if (layout.slot(j).type() == CycleLayout.Type.NETHER && seen++ == i) return layout.slot(j).core();
        }
        return 0;
    }

    public long endLen() {
        return Disintegration.bandLength(eFade, eVoid, layout == null ? eEnd : layout.firstCoreOf(CycleLayout.Type.END));
    }

    /** Combined length of the upside-down band ({@code 2·udFade + udHold}); 0 when the band is disabled. */
    public long upsideDownLen() {
        return 2L * Math.max(0, udFade) + Math.max(0, layout == null ? udHold : layout.firstCoreOf(CycleLayout.Type.UPSIDE_DOWN));
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
        return Math.max(0, layout == null ? chuncksHold : layout.firstCoreOf(CycleLayout.Type.CHUNCKS));
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

    /** Length of the spheres band core (the full-void {@code spheresHold}); 0 when disabled. */
    public long spheresLen() {
        return Math.max(0, layout == null ? spheresHold : layout.firstCoreOf(CycleLayout.Type.SPHERES));
    }

    /**
     * Length of the spheres entry fade zone before the band core; gated on {@code spheresLen > 0} so a
     * disabled band — or a zero {@code spheresFade} — keeps {@link #period()} byte-identical.
     */
    public long spheresFadeLen() {
        return spheresLen() > 0L ? Math.max(0, spheresFade) : 0L;
    }

    /**
     * Plain-overworld gap before the spheres band (after the chuncks core); gated on
     * {@code spheresLen > 0} so it collapses to 0 — and keeps {@link #period()} byte-identical — when
     * the band is disabled.
     */
    public long spheresLeadGapLen() {
        return spheresLen() > 0L ? Math.max(0, spheresLeadGap) : 0L;
    }

    /** Length of the stacks band core (the {@code stacksHold}); 0 when disabled. */
    public long stacksLen() {
        return Math.max(0, layout == null ? stacksHold : layout.firstCoreOf(CycleLayout.Type.STACKS));
    }

    /**
     * Length of the stacks entry fade zone before the band core; gated on {@code stacksLen > 0} so a
     * disabled band — or a zero {@code stacksFade} — keeps {@link #period()} byte-identical.
     */
    public long stacksFadeLen() {
        return stacksLen() > 0L ? Math.max(0, stacksFade) : 0L;
    }

    /**
     * Plain-overworld gap between the spheres band and the stacks entry fade; gated on
     * {@code stacksLen > 0} so it collapses to 0 — and keeps {@link #period()} byte-identical — when
     * the band is disabled.
     */
    public long stacksLeadGapLen() {
        return stacksLen() > 0L ? Math.max(0, stacksLeadGap) : 0L;
    }

    /**
     * {@code 2·owGap + netherLen + endLen + udLen + udExitFade + udExitGap + chuncksLeadGap + chuncksFade
     * + chuncksLen + spheresLeadGap + spheresFade + spheresLen + stacksLeadGap + stacksFade + stacksLen
     * + Σ legacy spans}.
     */
    public long period() {
        if (layout != null) return layout.period();
        return 2L * Math.max(0, owGap) + netherLen() + endLen()
                + upsideDownLen() + udExitFadeLen() + udExitGap()
                + chuncksLeadGapLen() + chuncksFadeLen() + chuncksLen()
                + spheresLeadGapLen() + spheresFadeLen() + spheresLen()
                + stacksLeadGapLen() + stacksFadeLen() + stacksLen()
                + legacyTotalLen();
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

    // ---- ordered-layout plumbing ------------------------------------------------------------
    // With a CycleLayout every band helper asks "which slot is worldX in, and how far into it" instead of
    // subtracting a chained start. Run k (doubling) is folded away first: the anchored offset is mapped to
    // its base coordinate u in [0, period) and every ramp is evaluated there, so cores AND fades stretch
    // ×2^k together. Classic (layout == null) keeps the chained-start arithmetic byte-for-byte.

    /** True when this cycle is an ordered slot layout rather than the classic fixed period. */
    public boolean hasLayout() {
        return layout != null;
    }

    /** Blocks past the anchor, or {@code -1} before it. Layout only (no phase shift: the first slot is explicit). */
    private long anchored(int worldX) {
        return worldX < startX ? -1L : (long) worldX - startX;
    }

    /** Doubling run index at {@code worldX} (0 before the anchor). Layout only. */
    private int runAt(int worldX) {
        long off = anchored(worldX);
        return off < 0L ? 0 : CycleLayout.runIndex(off, layout.period());
    }

    /** Base coordinate {@code u} of {@code worldX}, or {@code -1} before the anchor. Layout only. */
    private long baseAt(int worldX) {
        long off = anchored(worldX);
        return off < 0L ? -1L : CycleLayout.baseCoord(off, layout.period());
    }

    /** Slot index at {@code worldX} or {@code -1}. Layout only. */
    private int slotAt(int worldX) {
        long u = baseAt(worldX);
        return u < 0L ? -1 : layout.indexAt(u);
    }

    /** World X of base coordinate {@code u} in the run {@code worldX} is in — the inverse of {@link #baseAt}. */
    private long worldOf(int worldX, long u) {
        int k = runAt(worldX);
        return startX + CycleLayout.runStart(k, layout.period()) + (u << k);
    }

    /**
     * Offset into the span of band {@code t} at {@code worldX} — the whole slot, fades included — or
     * {@code -1} outside it. Classic spans are the chained-start segments the fixed period was always
     * made of; a layout answers from its slot table.
     */
    private long spanLocal(CycleLayout.Type t, int worldX) {
        if (layout != null) {
            int i = slotAt(worldX);
            if (i < 0 || layout.slot(i).type() != t) return -1L;
            return baseAt(worldX) - layout.start(i);
        }
        long o = offset(worldX);
        if (o < 0L) return -1L;
        long start;
        long len;
        switch (t) {
            case NETHER -> { start = netherStart(); len = netherLen(); }
            case END -> { start = endStart(); len = endLen(); }
            case UPSIDE_DOWN -> { start = udStart(); len = upsideDownLen() + udExitFadeLen() + udExitGap(); }
            case CHUNCKS -> { start = chuncksFadeStart(); len = chuncksFadeLen() + chuncksLen(); }
            case SPHERES -> { start = spheresFadeStart(); len = spheresFadeLen() + spheresLen(); }
            case STACKS -> { start = stacksFadeStart(); len = stacksFadeLen() + stacksLen(); }
            default -> { return -1L; }
        }
        long l = o - start;
        return (l < 0L || l >= len) ? -1L : l;
    }

    /** Core length of the {@code t} occurrence at {@code worldX} (layout), or the classic single core. */
    private int spanCore(CycleLayout.Type t, int worldX) {
        if (layout != null) {
            int i = slotAt(worldX);
            if (i >= 0 && layout.slot(i).type() == t) return layout.slot(i).core();
            return layout.firstCoreOf(t);
        }
        return switch (t) {
            case NETHER -> coreHold;
            case END -> eEnd;
            case UPSIDE_DOWN -> udHold;
            case CHUNCKS -> chuncksHold;
            case SPHERES -> spheresHold;
            case STACKS -> stacksHold;
            default -> 0;
        };
    }

    /** Style of the {@code t} occurrence at {@code worldX}; {@code null} when not in one (or classic). */
    private CycleLayout.Style styleAt(CycleLayout.Type t, int worldX) {
        if (layout == null) return null;
        int i = slotAt(worldX);
        if (i < 0 || layout.slot(i).type() != t) return null;
        return layout.slot(i).style();
    }

    /** Which look the Nether band at {@code worldX} wears ({@code BETTER} = BetterNether); {@code null} outside one. */
    public CycleLayout.Style netherStyleAt(int worldX) {
        return styleAt(CycleLayout.Type.NETHER, worldX);
    }

    /** Which look the End band at {@code worldX} wears ({@code BETTER} = BetterEnd); {@code null} outside one. */
    public CycleLayout.Style endStyleAt(int worldX) {
        return styleAt(CycleLayout.Type.END, worldX);
    }

    /** Which look the overworld gap at {@code worldX} wears ({@code WWOO} / {@code BOP}); {@code null} outside one. */
    public CycleLayout.Style overworldStyleAt(int worldX) {
        return styleAt(CycleLayout.Type.OVERWORLD, worldX);
    }

    /**
     * The look ({@code WWOO} / {@code BOP}) of a modded overworld gap that carries on into the band
     * transition at {@code worldX}, or {@code null}. The overworld-looking part of a band's transition
     * wears the look of the overworld gap it borders, so a modded stretch doesn't stop at a hard line:
     * <ul>
     *   <li>upside-down — the last sixth of the Reassembly ({@link #UD_BLEED_REASSEMBLY_FRACTION}) and
     *       the exit gap, from the gap after it (its entry is all mirror);</li>
     *   <li>Nether — the beach, mountain stages and core crossfade on each side, from that side's gap;</li>
     *   <li>End — the overworld→void erosion fade on each side, from that side's gap.</li>
     * </ul>
     * Layout only (classic cycles have no styled gaps). In base coordinates, so stretched runs scale.
     */
    public CycleLayout.Style bleedingOverworldStyleAt(int worldX) {
        if (layout == null) return null;
        int i = slotAt(worldX);
        if (i < 0) return null;
        CycleLayout.Slot slot = layout.slot(i);
        long local = baseAt(worldX) - layout.start(i);
        long len = layout.length(i);
        long side;
        switch (slot.type()) {
            case UPSIDE_DOWN -> {
                long bleedStart = udBandLenAt(worldX)
                        + Math.round(udExitFadeLenAt(worldX) * UD_BLEED_REASSEMBLY_FRACTION);
                return local >= bleedStart ? moddedOverworldStyle(i + 1) : null;
            }
            case NETHER -> side = (len - Math.max(0, slot.core())) / 2L;
            case END -> side = Math.max(0, eFade);
            default -> { return null; }
        }
        if (local < side) return moddedOverworldStyle(i - 1);
        if (local >= len - side) return moddedOverworldStyle(i + 1);
        return null;
    }

    /**
     * How far into the upside-down Reassembly the next gap's modded look begins. Most of it is still
     * visibly reassembling, so the look waits until the world has nearly settled (shipped layout:
     * X ≈ 22 994 instead of the Reassembly's start at 17 994).
     */
    static final double UD_BLEED_REASSEMBLY_FRACTION = 5.0 / 6.0;

    /** Style of slot {@code i} when it is a WWOO / BoP overworld gap, else {@code null}. */
    private CycleLayout.Style moddedOverworldStyle(int i) {
        if (i < 0 || i >= layout.count()) return null;
        CycleLayout.Slot s = layout.slot(i);
        if (s.type() != CycleLayout.Type.OVERWORLD) return null;
        return (s.style() == CycleLayout.Style.WWOO || s.style() == CycleLayout.Style.BOP) ? s.style() : null;
    }

    /**
     * 0-based occurrence pass of band {@code t} at {@code worldX}: {@code run × perRun + occurrence}, so the
     * second Nether of run 0 is pass 1 and the first Nether of run 1 is pass 2. Between occurrences it is
     * the last one started; {@code -1} before the anchor. Classic layouts (one occurrence per period) fall
     * back to {@link #cycleIndex}.
     */
    private long passIndex(CycleLayout.Type t, int worldX) {
        if (layout == null) return cycleIndex(worldX);
        long u = baseAt(worldX);
        if (u < 0L) return -1L;
        return (long) runAt(worldX) * layout.typeCount(t) + layout.occurrencesStarted(t, u);
    }

    /** The Nether-band pass at {@code worldX} — see {@link #passIndex} and {@link #isBetterNetherPass}. */
    public long netherPassIndex(int worldX) {
        return passIndex(CycleLayout.Type.NETHER, worldX);
    }

    /**
     * True if Nether pass {@code pass} is a BetterNether one. The single rule worldgen, advancements and
     * {@code /dtp} share: with a layout, the style the order gives that occurrence ({@code nether:better});
     * the classic layout keeps its alternation ({@link BetterNetherCoreBiomes#isBetterNetherPass}).
     */
    public boolean isBetterNetherPass(long pass) {
        if (layout == null) return BetterNetherCoreBiomes.isBetterNetherPass(pass);
        return occurrenceStyle(CycleLayout.Type.NETHER, pass) == CycleLayout.Style.BETTER;
    }

    /** True if End pass {@code pass} is a BetterEnd one — the End twin of {@link #isBetterNetherPass}. */
    public boolean isBetterEndPass(long pass) {
        if (layout == null) return EndBandStyle.isBetterEndPass(pass);
        return occurrenceStyle(CycleLayout.Type.END, pass) == CycleLayout.Style.BETTER;
    }

    /** {@link #isBetterNetherPass} of the Nether pass at {@code worldX} (the last one started between bands). */
    public boolean isBetterNetherAt(int worldX) {
        return isBetterNetherPass(netherPassIndex(worldX));
    }

    /** {@link #isBetterEndPass} of the End pass at {@code worldX} (the last one started between bands). */
    public boolean isBetterEndAt(int worldX) {
        return isBetterEndPass(endPassIndex(worldX));
    }

    /** Style of the occurrence behind {@code t}-pass {@code pass} ({@code run × perRun + occurrence}); {@code null} if none. */
    private CycleLayout.Style occurrenceStyle(CycleLayout.Type t, long pass) {
        int n = layout.typeCount(t);
        if (n == 0 || pass < 0L) return null;
        return layout.styleOfOccurrence(t, (int) (pass % n));
    }

    /**
     * World-X range {@code [start, end)} of Nether pass {@code pass} (the debug report): classic, the
     * band of period {@code pass}; layout, the {@code pass % perRun}-th Nether slot of run
     * {@code pass / perRun}, stretched by that run's doubling. {@code null} when there is no Nether band.
     */
    public long[] netherPassRange(int pass) {
        if (layout == null) {
            if (netherLen() <= 0L) return null;
            long start = startX - phaseShift + (long) pass * period() + netherStart();
            return new long[] {start, start + netherLen()};
        }
        int n = layout.typeCount(CycleLayout.Type.NETHER);
        if (n == 0 || pass < 0) return null;
        int k = pass / n;
        int occ = pass % n;
        for (int i = 0; i < layout.count(); i++) {
            if (layout.slot(i).type() != CycleLayout.Type.NETHER || layout.occurrence(i) != occ) continue;
            long runStart = startX + CycleLayout.runStart(k, layout.period());
            return new long[] {runStart + (layout.start(i) << k), runStart + ((layout.start(i) + layout.length(i)) << k)};
        }
        return null;
    }

    /** World-block length of the overworld gap that leads into Nether pass {@code pass} (classic: {@code owGap}). */
    public long overworldGapBefore(int pass) {
        return overworldGapBeside(pass, -1);
    }

    /** World-block length of the overworld gap that follows Nether pass {@code pass} (classic: {@code owGap}). */
    public long overworldGapAfter(int pass) {
        return overworldGapBeside(pass, +1);
    }

    /**
     * World-X ranges {@code [start, end)} of the overworld gaps in the first {@code laps} laps, in X
     * order — every overworld slot of runs {@code 0..laps-1} (layout), or each period's lead and
     * post-Nether gap (classic, the only gaps a stretch style can sit in). Clamped to the anchor;
     * empty gaps dropped. Pair with {@link SecondLapOverworld#at} to find a given look's stretches.
     */
    public List<long[]> overworldGapRanges(int laps) {
        List<long[]> out = new ArrayList<>();
        if (layout != null) {
            for (int k = 0; k < laps; k++) {
                long runStart = startX + CycleLayout.runStart(k, layout.period());
                for (int i = 0; i < layout.count(); i++) {
                    if (layout.slot(i).type() != CycleLayout.Type.OVERWORLD) continue;
                    addRange(out, runStart + (layout.start(i) << k),
                            runStart + ((layout.start(i) + layout.length(i)) << k));
                }
            }
            return out;
        }
        long p = period();
        if (p <= 0L) return out;
        for (int lap = 0; lap < laps; lap++) {
            long base = startX - phaseShift + (long) lap * p;
            addRange(out, base, base + netherStart());
            addRange(out, base + netherStart() + netherLen(), base + endStart());
        }
        return out;
    }

    private void addRange(List<long[]> out, long start, long end) {
        long s = Math.max(start, startX);
        if (s < end) out.add(new long[] {s, end});
    }

    private long overworldGapBeside(int pass, int dir) {
        if (layout == null) return Math.max(0, owGap);
        int n = layout.typeCount(CycleLayout.Type.NETHER);
        if (n == 0 || pass < 0) return 0L;
        int k = pass / n;
        int occ = pass % n;
        for (int i = 0; i < layout.count(); i++) {
            if (layout.slot(i).type() != CycleLayout.Type.NETHER || layout.occurrence(i) != occ) continue;
            int j = i + dir;
            if (j < 0 || j >= layout.count() || layout.slot(j).type() != CycleLayout.Type.OVERWORLD) return 0L;
            return layout.length(j) << k;
        }
        return 0L;
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
        return spanLocal(CycleLayout.Type.NETHER, worldX);
    }

    /** Length of the Nether occurrence at {@code worldX} (classic: {@link #netherLen}). */
    private long netherLenAt(int worldX) {
        return NetherTransition.bandLength(riseLen(), megaHold, coreFade, spanCore(CycleLayout.Type.NETHER, worldX));
    }

    /** Nether band-presence ramp (0 outside the nether segment) — drives in-band gating. */
    public double netherHeightRamp(int worldX) {
        long ln = netherOffset(worldX);
        if (ln < 0L) return 0.0;
        return NetherTransition.heightRamp((int) ln, 0L, riseLen(), megaHold, coreFade, spanCore(CycleLayout.Type.NETHER, worldX), 0);
    }

    /** Nether intensity ramp (netherrack → real Nether) at a world-X (0 outside the nether segment). */
    public double netherRamp(int worldX) {
        long ln = netherOffset(worldX);
        if (ln < 0L) return 0.0;
        return NetherTransition.netherRamp((int) ln, 0L, riseLen(), megaHold, coreFade, spanCore(CycleLayout.Type.NETHER, worldX), 0);
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
     * How many blocks past the leading edge of the real-Nether <b>core</b> {@code worldX} sits:
     * {@code 0} at the first full-Nether column (where the netherrack crossfade finishes), growing to
     * {@code coreHold - 1} at the last one, and {@code -1} anywhere else — both crossfades, the
     * mountain rise/plateau, and outside the nether segment entirely.
     *
     * <p>The layout offsets mirror {@link NetherTransition#netherRamp}: the crossfade-in runs
     * {@code [riseLen + megaHold, +coreFade)} and the core {@code [+coreFade, +coreHold)} after it. A
     * depth is what {@link #netherRamp} alone cannot give: the ramp reads {@code 1.0} across the whole
     * core, so it can say "in the Nether" but not "how far in". {@code NetherMobSpawner} uses this to
     * hold ghasts back until the player is properly inside the Nether rather than still crossfading
     * into it.</p>
     */
    public long netherCoreDepth(int worldX) {
        long ln = netherOffset(worldX);
        if (ln < 0L) return -1L;
        long coreStart = (long) riseLen() + Math.max(0, megaHold) + Math.max(0, coreFade);
        long d = ln - coreStart;
        return (d < 0L || d >= Math.max(0, spanCore(CycleLayout.Type.NETHER, worldX))) ? -1L : d;
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
        long band = netherLenAt(worldX);
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
        long trail = netherLenAt(worldX) - ln;                     // blocks to the trailing gate
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
        if (layout != null) {
            long u = baseAt(worldX);
            if (u < 0L) return Long.MIN_VALUE;
            // The Nether slot at or before u in this run; before the run's first, the run start stands in.
            long entrance = 0L;
            for (int i = 0; i < layout.count() && layout.start(i) <= u; i++) {
                if (layout.slot(i).type() == CycleLayout.Type.NETHER) entrance = layout.start(i);
            }
            return worldOf(worldX, entrance);
        }
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
        return influence().nether(worldX, margin);
    }

    /**
     * True iff {@code worldX} falls inside the End segment of some cycle repeat — plain membership, no
     * margin (the End band is evaluated at the un-waved X everywhere: {@code endMiddleRamp},
     * {@code endIslandRamp}, {@code isEndCore}). A {@code false} guarantees all End ramps are 0 at
     * {@code worldX}, letting hot paths skip them entirely. Same conservative O(1) contract as
     * {@link #netherInfluence}.
     */
    public boolean endSegmentInfluence(long worldX) {
        return influence().end(worldX);
    }

    /**
     * Precomputed influence geometry for the per-sample hot loops: {@link #netherInfluence}/
     * {@link #endSegmentInfluence} are O(1), but deriving their inputs ({@code period()},
     * {@code netherLen()}, {@code netherStart()}, …) walks a chain of sum/clamp methods — measured
     * as the dominant predicate cost at ~37k calls per chunk cell in {@code fillArray}. This record
     * snapshots those five longs once so the hot predicates are a handful of compares + two
     * {@code floorMod}s. Immutable, derived purely from the (immutable) cycle → safe to share
     * across worldgen threads.
     */
    public record Influence(WorldGenCycle owner, long period,
                            long netherStart, long netherLen, long endStart, long endLen) {

        /** {@link WorldGenCycle#netherInfluence} evaluated against the snapshot — byte-identical. */
        public boolean nether(long worldX, int margin) {
            if (owner.layout != null) return owner.layoutInfluence(CycleLayout.Type.NETHER, worldX, margin);
            return contains(worldX, margin, netherStart, netherLen);
        }

        /** {@link WorldGenCycle#endSegmentInfluence} evaluated against the snapshot — byte-identical. */
        public boolean end(long worldX) {
            if (owner.layout != null) return owner.layoutInfluence(CycleLayout.Type.END, worldX, 0);
            return contains(worldX, 0, endStart, endLen);
        }

        /**
         * The circular-window intersection: does the inclusive window {@code [worldX − margin,
         * worldX + margin]}, clamped to the anchor and folded into cycle offsets, intersect the
         * segment {@code [segStart, segStart + segLen)}? Two circular arcs intersect iff either
         * contains the other's start point — both checks via {@code floorMod} forward distances,
         * exact for any window/segment phase including period wrap.
         */
        private boolean contains(long worldX, int margin, long segStart, long segLen) {
            if (segLen <= 0L || period <= 0L) return false;
            long m = Math.max(0, margin);
            long b = worldX + m;
            if (b < owner.startX) return false;
            long a = Math.max(worldX - m, owner.startX);
            long w = b - a;
            if (w >= period) return true;
            long oa = Math.floorMod(a - owner.startX + owner.phaseShift, period);
            if (Math.floorMod(segStart - oa, period) <= w) return true;
            return Math.floorMod(oa - segStart, period) < segLen;
        }
    }

    /**
     * Single-slot identity cache for {@link #influence()} — in practice one cycle instance is live
     * (the {@link #fromConfig} memo), so one slot suffices; a different instance (unit tests, a
     * config reload) just recomputes and replaces. The whole pair is one volatile reference, so a
     * racing thread can never observe a snapshot paired with the wrong owner.
     */
    private static volatile Influence influenceCache;

    /**
     * Layout form of the influence test: does any {@code t} slot overlap the window
     * {@code [worldX − margin, worldX + margin]}? Conservative — a window straddling two doubling runs
     * answers {@code true} rather than folding both.
     */
    boolean layoutInfluence(CycleLayout.Type t, long worldX, int margin) {
        long m = Math.max(0, margin);
        long b = worldX + m;
        if (b < startX) return false;
        long a = Math.max(worldX - m, startX);
        long p = layout.period();
        long offA = a - startX;
        long offB = b - startX;
        int ka = CycleLayout.runIndex(offA, p);
        if (ka != CycleLayout.runIndex(offB, p)) return true;
        long rs = CycleLayout.runStart(ka, p);
        return layout.anyOfTypeIn(t, (offA - rs) >> ka, (offB - rs) >> ka);
    }

    /** The precomputed {@link Influence} snapshot for this cycle (identity-cached, thread-safe). */
    public Influence influence() {
        Influence inf = influenceCache;
        if (inf == null || inf.owner() != this) {
            inf = new Influence(this, period(), layout == null ? netherStart() : 0L, netherLen(),
                    layout == null ? endStart() : 0L, endLen());
            influenceCache = inf;                             // benign race — same-value replace
        }
        return inf;
    }

    /** End erosion / sky ramp at a world-X (0 outside the End segment). */
    public double endMiddleRamp(int worldX) {
        long le = spanLocal(CycleLayout.Type.END, worldX);
        if (le < 0L) return 0.0;
        return Disintegration.middleRamp((int) le, 0L, eFade, eVoid, spanCore(CycleLayout.Type.END, worldX), 0);
    }

    /** End-island fill ramp at a world-X (0 outside the End segment). */
    public double endIslandRamp(int worldX) {
        long le = spanLocal(CycleLayout.Type.END, worldX);
        if (le < 0L) return 0.0;
        return Disintegration.endRamp((int) le, 0L, eFade, eVoid, spanCore(CycleLayout.Type.END, worldX), 0);
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
        if (layout != null) {
            long off = anchored(worldX);
            return off < 0L ? -1L : CycleLayout.runIndex(off, layout.period());   // the doubling run index
        }
        long p = period();
        if (p <= 0L || worldX < startX) return -1L;
        return Math.floorDiv((long) worldX - startX + phaseShift, p);
    }

    /** Which plain-overworld gap a world-X sits in — see {@link #overworldGapAt}. */
    public enum OverworldGap { LEAD, POST_NETHER, NONE }

    /**
     * The overworld gap on either side of the Nether band at this world-X: {@link OverworldGap#LEAD} for
     * the {@code owGap} that opens every cycle and runs into the Nether band, {@link OverworldGap#POST_NETHER}
     * for the {@code owGap} between the Nether band and the End band, {@link OverworldGap#NONE} anywhere
     * else (a special band, the later lead gaps, or before the anchor). Pair with {@link #cycleIndex}
     * for the lap. The lead gap of cycle 0 is shortened by {@code phaseShift} — it starts at the anchor.
     */
    public OverworldGap overworldGapAt(int worldX) {
        if (layout != null) {
            int i = slotAt(worldX);
            if (i < 0 || layout.slot(i).type() != CycleLayout.Type.OVERWORLD) return OverworldGap.NONE;
            if (i + 1 < layout.count() && layout.slot(i + 1).type() == CycleLayout.Type.NETHER) return OverworldGap.LEAD;
            if (i > 0 && layout.slot(i - 1).type() == CycleLayout.Type.NETHER) return OverworldGap.POST_NETHER;
            return OverworldGap.NONE;
        }
        long o = offset(worldX);
        if (o < 0L) return OverworldGap.NONE;
        if (o < netherStart()) return OverworldGap.LEAD;
        long postStart = netherStart() + netherLen();
        if (o >= postStart && o < endStart()) return OverworldGap.POST_NETHER;
        return OverworldGap.NONE;
    }

    /**
     * True when this world-X is ordinary overworld — no band (fades included) and no legacy era over
     * it. Before the anchor counts: the cycle has not started there. Says nothing about which look the
     * stretch wears; pair with {@link SecondLapOverworld#at} for that.
     */
    public boolean isOverworldGapAt(int worldX) {
        if (layout != null) {
            int i = slotAt(worldX);
            return i < 0 || layout.slot(i).type() == CycleLayout.Type.OVERWORLD;
        }
        for (CycleLayout.Type t : CycleLayout.Type.values()) {
            if (t == CycleLayout.Type.OVERWORLD || t == CycleLayout.Type.LEGACY_RUN) continue;
            if (spanLocal(t, worldX) >= 0L) return false;
        }
        return legacyAt(worldX) == null;
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
        return passIndex(CycleLayout.Type.END, worldX);
    }

    /**
     * End sky/fog ramp at a world-X — like {@link #endMiddleRamp} but with both fades pushed
     * {@code skyOffset} blocks toward the void core, so the End sky lags the terrain erosion
     * (delayed fade-in on entry, early fade-out on exit). 0 outside the End segment;
     * {@code skyOffset == 0} reproduces {@link #endMiddleRamp} exactly.
     */
    public double endSkyRamp(int worldX, int skyOffset) {
        long le = spanLocal(CycleLayout.Type.END, worldX);
        if (le < 0L) return 0.0;
        return Disintegration.skyRamp((int) le, 0L, eFade, eVoid, spanCore(CycleLayout.Type.END, worldX), 0, skyOffset);
    }

    /** Offset into the upside-down band at a world-X, or {@code -1} outside it. */
    private long udOffset(int worldX) {
        long l = spanLocal(CycleLayout.Type.UPSIDE_DOWN, worldX);
        return (l < 0L || l >= udBandLenAt(worldX)) ? -1L : l;
    }

    /** {@code 2·udFade + core} of the upside-down occurrence at {@code worldX}. */
    private long udBandLenAt(int worldX) {
        return 2L * Math.max(0, udFade) + Math.max(0, spanCore(CycleLayout.Type.UPSIDE_DOWN, worldX));
    }

    /** Reassembly (exit-crossfade) length of the upside-down occurrence at {@code worldX}. */
    private long udExitFadeLenAt(int worldX) {
        if (layout == null) return udExitFadeLen();
        int i = slotAt(worldX);
        if (i >= 0 && layout.slot(i).type() == CycleLayout.Type.UPSIDE_DOWN) return layout.udReassembly(layout.slot(i));
        return udExitFadeLen();
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
            long band = udBandLenAt(worldX);
            if (lu < fade) return (double) lu / fade;          // leading fade-in
            long holdEnd = band - fade;
            if (lu < holdEnd) return 1.0;                      // core hold
            if (udExitFadeLenAt(worldX) > 0L) return 1.0;      // exit crossfade present → hold at 1, it carries the fade-out
            return Math.max(0.0, (double) (band - lu) / fade); // trailing fade-out (byte-identical when no exit fade)
        }
        long ex = udExitFadeOffset(worldX);                    // exit crossfade: sky/light fades 1→0 across the whole zone
        if (ex >= 0L) {
            long len = udExitFadeLenAt(worldX);
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
        return udEntryLeadLocal(worldX) >= 0L;
    }

    /**
     * Offset into the upside-down entry lead at {@code worldX}, or {@code -1}. The lead is the last
     * {@link #udEntryLeadLen} blocks of the End band <em>when the upside-down band follows it directly</em> —
     * always so in the classic order; in a layout only where the next slot is upside-down (lap 1), never
     * where the End flows on into the spheres (lap 2).
     */
    private long udEntryLeadLocal(int worldX) {
        long lead = udEntryLeadLen();
        if (lead <= 0L) return -1L;
        if (layout != null) {
            int i = slotAt(worldX);
            if (i < 0 || layout.slot(i).type() != CycleLayout.Type.END) return -1L;
            if (i + 1 >= layout.count() || layout.slot(i + 1).type() != CycleLayout.Type.UPSIDE_DOWN) return -1L;
            long l = baseAt(worldX) - (layout.start(i) + layout.length(i) - lead);
            return (l < 0L || l >= lead) ? -1L : l;
        }
        long o = offset(worldX);
        if (o < 0L) return -1L;
        long l = o - udEntryLeadStart();
        return (l < 0L || l >= lead) ? -1L : l;
    }

    /**
     * Reveal ramp {@code 0..1} across the entry lead-in zone: 0 at {@code udEntryLeadStart} (start of
     * the void-hold approach), linear up to 1 at {@code udStart} (where the true band's full mirror
     * takes over). 0 outside the zone. Drives the noise-gated partial terrain mirror in
     * {@code WorldUpsideDownEvents} — the terrain analogue of {@link #upsideDownRamp}'s atmosphere fade.
     */
    public double upsideDownEntryRevealRamp(int worldX) {
        long l = udEntryLeadLocal(worldX);
        if (l < 0L) return 0.0;
        return (double) l / udEntryLeadLen();
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
        long sl = spanLocal(CycleLayout.Type.UPSIDE_DOWN, worldX);
        if (sl < 0L) return -1L;
        long l = sl - udBandLenAt(worldX);
        return (l < 0L || l >= udExitFadeLenAt(worldX)) ? -1L : l;
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
        long len = udExitFadeLenAt(worldX);
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
        long len = udExitFadeLenAt(worldX);
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
        return coreLocal(CycleLayout.Type.CHUNCKS, chuncksFadeLen(), worldX);
    }

    /** Offset into the core of the fade-in band {@code t} at {@code worldX} (its span = fade + core), or {@code -1}. */
    private long coreLocal(CycleLayout.Type t, long fade, int worldX) {
        long l = spanLocal(t, worldX);
        if (l < 0L) return -1L;
        long c = l - fade;
        return (c < 0L || c >= spanCore(t, worldX)) ? -1L : c;
    }

    /**
     * Fade-in ramp of band {@code t} at {@code worldX}: {@code 0 → 1} across the entry fade, {@code 1} in the
     * core, {@code 0} elsewhere. The chuncks, spheres and stacks bands all enter this way.
     */
    private double fadeInRamp(CycleLayout.Type t, long fade, int worldX) {
        long l = spanLocal(t, worldX);
        if (l < 0L) return 0.0;
        if (l >= fade) return 1.0;
        return fade > 0L ? (double) l / fade : 1.0;
    }

    /**
     * Layout form of the approach-or-band windows: from the end of the nearest earlier non-overworld slot
     * through the end of the {@code t} slot (any occurrence). Gates "Re-Over-World".
     */
    private boolean layoutApproachOrBand(CycleLayout.Type t, int worldX) {
        long u = baseAt(worldX);
        if (u < 0L) return false;
        for (int j = 0; j < layout.count(); j++) {
            if (layout.slot(j).type() != t) continue;
            if (u >= layout.approachStart(j) && u < layout.start(j) + layout.length(j)) return true;
        }
        return false;
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
     * True if {@code worldX} lies anywhere in the run-up to the chuncks band or the band core itself —
     * the whole stretch from the end of the upside-down exit crossfade ({@code udExitGap}, the chuncks
     * {@code leadGap}, the entry fade, then the core). The intervening gaps read as plain overworld to
     * {@link DisintegrationBand#zoneAt}, but the world has not settled back yet: the chunks are still to
     * come. Used by the {@code reached_overworld_again} advancement gate ({@code ZoneProgressEvents}) so
     * "Re-Over-World" waits for the overworld that follows the band, not the one leading into it.
     *
     * <p>False when the chuncks band is disabled ({@code chuncksLen == 0}) — with no band there is
     * nothing to wait for, and the pre-chuncks gating is preserved exactly.</p>
     */
    public boolean isInChuncksApproachOrBand(int worldX) {
        if (chuncksLen() <= 0L) return false;
        if (layout != null) return layoutApproachOrBand(CycleLayout.Type.CHUNCKS, worldX);
        long o = offset(worldX);
        if (o < 0L) return false;
        long approachStart = udExitFadeStart() + udExitFadeLen();
        return o >= approachStart && o < chuncksStart() + chuncksLen();
    }

    /**
     * Effective keep-density at a world-X, driving the entry transition: {@code chuncksKeepDensity}
     * across the band core, ramping linearly from {@code 1.0} (all real terrain, no void) at the entry
     * fade start up to {@code chuncksKeepDensity} at the core edge, and {@code 1.0} everywhere else (so
     * chunks outside the band + fade are always kept). Pure (seed-independent), like the other ramps.
     */
    public double chuncksKeepDensityAt(int worldX) {
        if (chuncksLen() <= 0L) return 1.0;                         // band disabled → all real terrain
        double t = fadeInRamp(CycleLayout.Type.CHUNCKS, chuncksFadeLen(), worldX);   // 0 at fade start → 1 at core edge
        return 1.0 + (chuncksKeepDensity - 1.0) * t;                // lerp 1 → keepDensity (1.0 outside the band + fade)
    }

    // ---- spheres band --------------------------------------------------------

    /**
     * Offset (into the cycle) where the spheres entry fade zone begins — after the chuncks band core
     * and the spheres lead-in gap ({@code spheresLeadGap}). When the chuncks band is disabled all its
     * spans are 0, so this collapses to right after the upside-down exit gap (plus the lead gap); the
     * spheres band's placement is independent of whether chuncks is present.
     */
    private long spheresFadeStart() {
        return chuncksStart() + chuncksLen() + spheresLeadGapLen();
    }

    /** Offset where the full-void spheres core begins — after the entry fade zone. */
    private long spheresStart() {
        return spheresFadeStart() + spheresFadeLen();
    }

    /** Offset into the spheres band core at a world-X, or {@code -1} outside it. */
    /**
     * Blocks into the spheres band core at {@code worldX} ({@code 0} = first core column), or {@code -1}
     * outside the core (the entry fade included). The coordinate {@link SpheresSegments} offsets use.
     */
    public long spheresCoreOffset(int worldX) {
        return spheresOffset(worldX);
    }

    private long spheresOffset(int worldX) {
        return coreLocal(CycleLayout.Type.SPHERES, spheresFadeLen(), worldX);
    }

    /**
     * True if {@code worldX} lies in the full-void spheres band core (not the entry fade). Membership is
     * binary (per-column) like {@link #isInChuncksBand}; which blocks survive inside a column is the
     * seed-stable sphere field ({@code SphereField}) applied on top, not part of the pure layout.
     */
    public boolean isInSpheresBand(int worldX) {
        return spheresOffset(worldX) >= 0L;
    }

    /**
     * True if {@code worldX} lies in the entry fade zone {@code [spheresFadeStart, spheresStart)} —
     * immediately before the spheres core, where the natural terrain dissolves into void. Disjoint from
     * {@link #isInSpheresBand}: a column is in at most one of the two.
     */
    public boolean isInSpheresFade(int worldX) {
        long l = spanLocal(CycleLayout.Type.SPHERES, worldX);
        return l >= 0L && l < spheresFadeLen();
    }

    /**
     * Void ramp {@code 0..1} for the spheres band: {@code 0} outside the band, climbing linearly from
     * {@code 0} at the entry fade start to {@code 1} at the core edge, and held at {@code 1} across the
     * core. Drives how much of the natural terrain outside the spheres is dissolved (the carve pass
     * dithers removal against it) and the bedrock-floor skip. Pure (seed-independent), like the other
     * ramps. Hard far edge: {@code 0} again immediately after the core.
     */
    public double spheresVoidRamp(int worldX) {
        if (spheresLen() <= 0L) return 0.0;
        return fadeInRamp(CycleLayout.Type.SPHERES, spheresFadeLen(), worldX);
    }

    /**
     * End sky/light ramp {@code 0..1} over the <b>later part</b> of the spheres band core: {@code 0}
     * across the entry fade and the core's first {@code startBlocks} (normal overworld sky), climbing
     * {@code 0 → 1} over {@code fade} blocks from there, held at {@code 1}, then back {@code 1 → 0} over
     * the core's last {@code fade} blocks — so the overworld sky is fully restored exactly where the
     * terrain snaps back to overworld at the core's hard far edge. {@code startBlocks} at or past the
     * core length means no End sky at all; {@code fade} is clamped to a quarter of the core and {@code 0}
     * is a hard switch. Pure (seed-independent), like the other ramps.
     */
    public double spheresEndSkyRamp(int worldX, int startBlocks, int fade) {
        return spheresSkyWindowRamp(worldX, startBlocks, Long.MAX_VALUE, fade);
    }

    /**
     * Sky ramp {@code 0..1} over a window {@code [startBlocks, endBlocks)} of the spheres band core
     * (offsets from the first core column): climbs {@code 0 → 1} over {@code fade} blocks from
     * {@code startBlocks}, holds, then falls {@code 1 → 0} over the {@code fade} blocks before
     * {@code endBlocks}. {@code endBlocks} is clamped to the core length, so a window running to the
     * band's end hands back to the overworld sky exactly at the core's hard far edge. {@code fade} is
     * clamped to a quarter of the core; {@code 0} is a hard switch. Two windows that meet at {@code B}
     * crossfade when one ends at {@code B + fade/2} and the other starts at {@code B − fade/2}.
     */
    public double spheresSkyWindowRamp(int worldX, long startBlocks, long endBlocks, int fade) {
        long len = spheresLen();
        if (len <= 0L) return 0.0;
        long ls = spheresOffset(worldX);
        if (ls < 0L) return 0.0;
        long start = Math.max(0L, startBlocks);
        long end = Math.min(len, endBlocks);
        if (ls < start || ls >= end) return 0.0;
        long f = Math.max(0L, Math.min(fade, len / 4L));
        if (f == 0L) return 1.0;
        double in = (double) (ls - start + 1L) / f;           // entering: reaches 1 after f blocks
        double out = (double) (end - ls) / f;                 // leaving: 1/f on the window's last column
        return Math.min(1.0, Math.min(in, out));
    }

    /**
     * True if {@code worldX} lies anywhere in the run-up to the spheres band or the band core itself —
     * the whole stretch from the end of the chuncks core (the spheres {@code leadGap}, the entry fade,
     * then the core). The lead gap reads as plain overworld to {@link DisintegrationBand#zoneAt} and to
     * {@code ChuncksBand.isInApproachOrBand}, but the world has not settled back yet: the spheres are
     * still to come. Used by the {@code reached_overworld_again} advancement gate
     * ({@code ZoneProgressEvents}) alongside the chuncks predicate, so "Re-Over-World" waits for the
     * overworld that follows the LAST band of the cycle.
     *
     * <p>False when the spheres band is disabled ({@code spheresLen == 0}) — with no band there is
     * nothing to wait for, and the pre-spheres gating is preserved exactly.</p>
     */
    public boolean isInSpheresApproachOrBand(int worldX) {
        if (spheresLen() <= 0L) return false;
        if (layout != null) return layoutApproachOrBand(CycleLayout.Type.SPHERES, worldX);
        long o = offset(worldX);
        if (o < 0L) return false;
        long approachStart = chuncksStart() + chuncksLen();
        return o >= approachStart && o < spheresStart() + spheresLen();
    }

    // ---- stacks band -----------------------------------------------------------

    /**
     * Offset (into the cycle) where the stacks entry fade zone begins — after the spheres band core and
     * the stacks lead-in gap. When the spheres band is disabled all its spans are 0, so this collapses to
     * right after the chuncks core (plus the lead gap), and likewise past chuncks when that is off too;
     * the stacks band's placement is independent of which earlier bands are present.
     */
    private long stacksFadeStart() {
        return spheresStart() + spheresLen() + stacksLeadGapLen();
    }

    /** Offset where the stacks band core begins — after the entry fade zone. */
    private long stacksStart() {
        return stacksFadeStart() + stacksFadeLen();
    }

    /** Offset into the stacks band core at a world-X, or {@code -1} outside it. */
    private long stacksOffset(int worldX) {
        return coreLocal(CycleLayout.Type.STACKS, stacksFadeLen(), worldX);
    }

    /**
     * True if {@code worldX} lies in the stacks band core (not the entry fade). Membership is binary
     * (per-column) like {@link #isInChuncksBand}; the per-<em>chunk</em> terrain/void/stack decision is a
     * seed-stable noise gate applied on top of the {@link #stacksVoidRampAt void ramp} (see
     * {@code StacksBand}), not part of the pure layout.
     */
    public boolean isInStacksBand(int worldX) {
        return stacksOffset(worldX) >= 0L;
    }

    /**
     * True if {@code worldX} lies anywhere from the end of the spheres band core through the stacks lead
     * gap, entry fade and core. The lead gap reads as plain overworld to {@link DisintegrationBand#zoneAt},
     * but the world has not settled back yet: the towers are still to come. Used by the
     * {@code reached_overworld_again} advancement gate ({@code ZoneProgressEvents}) so "Re-Over-World"
     * waits for the overworld that follows the LAST band of the cycle.
     *
     * <p>False when the stacks band is disabled ({@code stacksLen == 0}) — with no band there is nothing
     * to wait for, and the pre-stacks gating is preserved exactly.</p>
     */
    public boolean isInStacksApproachOrBand(int worldX) {
        if (stacksLen() <= 0L) return false;
        if (layout != null) return layoutApproachOrBand(CycleLayout.Type.STACKS, worldX);
        long o = offset(worldX);
        if (o < 0L) return false;
        long approachStart = spheresStart() + spheresLen();
        return o >= approachStart && o < stacksStart() + stacksLen();
    }

    /**
     * Fraction {@code 0..1} of chunks that become void at a world-X, driving the entry transition:
     * {@code 1.0} across the band core (every chunk is void, some holding a stack), ramping linearly
     * from {@code 0.0} (all real terrain) at the entry fade start up to {@code 1.0} at the core edge,
     * and {@code 0.0} everywhere else. Pure (seed-independent), like the other ramps.
     */
    public double stacksVoidRampAt(int worldX) {
        if (stacksLen() <= 0L) return 0.0;                          // band disabled → all real terrain
        return fadeInRamp(CycleLayout.Type.STACKS, stacksFadeLen(), worldX);   // 0 at fade start → 1 in the core
    }

    // ---- legacy bands ------------------------------------------------------------

    /** Shared empty legacy layout for the back-compat constructors. */
    private static final LegacySpan[] NO_LEGACY = new LegacySpan[0];

    /**
     * What a legacy chunk at a world-X may be: it rolls {@code to} with probability {@code t}, else
     * {@code from}; {@code null} on either side means modern terrain. So a core is {@code (era, era, 1)},
     * the entry fade {@code (null, era, t)}, an era-to-era crossfade {@code (a, b, t)} — never modern —
     * and the exit fade {@code (era, null, t)}. {@link #kind()} and {@link #ramp()} are the old
     * one-band view: the era in play and the probability of it.
     */
    public record LegacyHit(LegacyBandKind from, LegacyBandKind to, double t) {

        /** Classic entry/core form: modern → {@code kind} with probability {@code ramp}. */
        public LegacyHit(LegacyBandKind kind, double ramp) {
            this(ramp >= 1.0 ? kind : null, kind, ramp);
        }

        /** The era in play (the destination, or the source on the exit fade). */
        public LegacyBandKind kind() {
            return to != null ? to : from;
        }

        /** Probability of {@link #kind()}. */
        public double ramp() {
            return to != null ? t : 1.0 - t;
        }
    }

    /** Combined length of every legacy span; 0 when none is enabled. */
    public long legacyTotalLen() {
        if (layout != null) {
            int i = layout.firstIndexOf(CycleLayout.Type.LEGACY_RUN);
            return i < 0 ? 0L : layout.length(i);
        }
        if (legacy == null) return 0L;
        long total = 0L;
        for (LegacySpan span : legacy) total += span.totalLen();
        return total;
    }

    /** Offset (into the cycle) where the first legacy span's lead gap begins — right after the stacks core. */
    private long legacyBase() {
        return stacksStart() + stacksLen();
    }

    /** The enabled span for {@code kind}, or {@code null}. */
    private LegacySpan spanOf(LegacyBandKind kind) {
        if (legacy == null) return null;
        for (LegacySpan span : legacy) {
            if (span.kind() == kind && span.holdLen() > 0L) return span;
        }
        return null;
    }

    /** Offset where {@code kind}'s lead gap begins, or {@code -1} when that band is disabled. */
    private long legacySlotStart(LegacyBandKind kind) {
        long start = legacyBase();
        for (LegacySpan span : legacy == null ? NO_LEGACY : legacy) {
            if (span.kind() == kind) return span.holdLen() > 0L ? start : -1L;
            start += span.totalLen();
        }
        return -1L;
    }

    /** Length of the legacy band {@code kind}'s core, or 0 when it is disabled. */
    public long legacyLen(LegacyBandKind kind) {
        if (layout != null) {
            int e = layout.eraIndex(kind);
            return e < 0 ? 0L : layout.eraCoreLen(e);
        }
        LegacySpan span = spanOf(kind);
        return span == null ? 0L : span.holdLen();
    }

    /**
     * The legacy band at {@code worldX} and how strongly the old generator applies there: {@code 1.0}
     * across the core, ramping linearly {@code 0 → 1} over the entry fade and {@code 1 → 0} over the exit
     * fade. {@code null} outside every legacy band (and in the lead gaps). Pure, seed-independent — the
     * per-chunk old/new decision is a seed-stable roll against this ramp (see {@code LegacyBands}).
     */
    public LegacyHit legacyAt(int worldX) {
        if (layout != null) return layoutLegacyAt(worldX);
        if (legacy == null || legacy.length == 0) return null;
        long o = offset(worldX);
        if (o < 0L) return null;
        long start = legacyBase();
        for (LegacySpan span : legacy) {
            long total = span.totalLen();
            if (total > 0L && o >= start && o < start + total) {
                double ramp = legacyRamp(span, o - start);
                return ramp > 0.0 ? new LegacyHit(span.kind(), ramp) : null;
            }
            start += total;
        }
        return null;
    }

    // ---- legacy run (ordered layout): entry fade, era cores with one shared crossfade between, exit fade.

    /** Local offset into the legacy-run slot at {@code worldX}, or {@code -1}. Layout only. */
    private long legacyRunLocal(int worldX) {
        return spanLocal(CycleLayout.Type.LEGACY_RUN, worldX);
    }

    private LegacyHit layoutLegacyAt(int worldX) {
        long l = legacyRunLocal(worldX);
        if (l < 0L) return null;
        LegacySpan[] eras = layout.eras();
        long f = layout.legacyFade();
        long at = 0L;
        LegacyBandKind prev = null;
        for (int e = 0; e <= eras.length; e++) {
            LegacyBandKind next = e < eras.length ? eras[e].kind() : null;
            if (l < at + f) {                                      // the fade / crossfade before era e
                double t = (double) (l - at + 1) / (f + 1);
                return new LegacyHit(prev, next, t);
            }
            at += f;
            if (next == null) break;
            long len = eras[e].holdLen();
            if (l < at + len) return new LegacyHit(next, next, 1.0); // era e core
            at += len;
            prev = next;
        }
        return null;
    }

    /** Offset of {@code kind}'s core from the legacy-run slot start, or {@code -1} when not in the run. Layout only. */
    private long eraCoreStart(LegacyBandKind kind) {
        int e = layout.eraIndex(kind);
        return e < 0 ? -1L : layout.eraCoreStart(e);
    }

    /** Ramp at {@code local} blocks into {@code span}'s slot (lead gap first). */
    static double legacyRamp(LegacySpan span, long local) {
        long fadeStart = span.leadGapLen();
        long holdStart = fadeStart + span.fadeLen();
        long holdEnd = holdStart + span.holdLen();
        long exitEnd = holdEnd + span.fadeLen();
        if (local < fadeStart || local >= exitEnd) return 0.0;
        if (local >= holdStart && local < holdEnd) return 1.0;
        if (local < holdStart) return (double) (local - fadeStart + 1) / (span.fadeLen() + 1);
        return (double) (exitEnd - local) / (span.fadeLen() + 1);
    }

    /**
     * How far {@code worldX} is through legacy band {@code kind}, {@code 0..1} from the start of its entry
     * fade to the end of its exit fade (the stretch where its chunks can appear), or {@code -1} outside it
     * (lead gap, other bands, disabled). Lets one band step through sub-versions along its length.
     */
    public double legacyProgress(LegacyBandKind kind, int worldX) {
        if (layout != null) {
            long l = legacyRunLocal(worldX);
            long cs = eraCoreStart(kind);
            if (l < 0L || cs < 0L) return -1.0D;
            long f = layout.legacyFade();
            long from = cs - f;
            long len = 2L * f + legacyLen(kind);
            long local = l - from;
            return (local < 0L || local >= len) ? -1.0D : (double) local / len;
        }
        LegacySpan span = spanOf(kind);
        if (span == null) return -1.0D;
        long o = offset(worldX);
        if (o < 0L) return -1.0D;
        long from = legacySlotStart(kind) + span.leadGapLen();
        long len = 2L * span.fadeLen() + span.holdLen();
        long local = o - from;
        if (local < 0L || local >= len) return -1.0D;
        return (double) local / len;
    }

    /** True if {@code worldX} lies in the core of legacy band {@code kind} (not its fades). */
    public boolean isInLegacyBand(LegacyBandKind kind, int worldX) {
        if (layout != null) {
            long l = legacyRunLocal(worldX);
            long cs = eraCoreStart(kind);
            return l >= 0L && cs >= 0L && l >= cs && l < cs + legacyLen(kind);
        }
        LegacySpan span = spanOf(kind);
        if (span == null) return false;
        long o = offset(worldX);
        if (o < 0L) return false;
        long holdStart = legacySlotStart(kind) + span.leadGapLen() + span.fadeLen();
        return o >= holdStart && o < holdStart + span.holdLen();
    }

    /**
     * {@link #legacyLen} in <em>world</em> blocks for the run {@code worldX} is in — the same number on
     * run 0, doubled on each later run — so a generator laying its script along the core
     * ({@code FarLandsShift}) stretches with the band.
     */
    public long legacyCoreLenBlocks(LegacyBandKind kind, int worldX) {
        long len = legacyLen(kind);
        return layout == null ? len : len << runAt(worldX);
    }

    /** Sentinel for {@link #legacyCoreStartX}: {@code worldX} is outside that band's slot. */
    public static final long NOT_IN_LEGACY_SLOT = Long.MIN_VALUE;

    /**
     * World X where the core of the legacy band {@code kind} instance containing {@code worldX} begins —
     * the anchor for a generator that lays its terrain out along the band (Far Lands: where the wall
     * falls). Defined across the whole slot (lead gap, both fades, core), so the entry-fade chunks share
     * the core's anchor; {@link #NOT_IN_LEGACY_SLOT} outside it or when the band is disabled.
     */
    public long legacyCoreStartX(LegacyBandKind kind, int worldX) {
        if (layout != null) {
            long l = legacyRunLocal(worldX);
            long cs = eraCoreStart(kind);
            if (l < 0L || cs < 0L) return NOT_IN_LEGACY_SLOT;
            long f = layout.legacyFade();
            if (l < cs - f || l >= cs + legacyLen(kind) + f) return NOT_IN_LEGACY_SLOT;
            int i = slotAt(worldX);
            return worldOf(worldX, layout.start(i) + cs);
        }
        LegacySpan span = spanOf(kind);
        if (span == null) return NOT_IN_LEGACY_SLOT;
        long o = offset(worldX);
        if (o < 0L) return NOT_IN_LEGACY_SLOT;
        long start = legacySlotStart(kind);
        if (o < start || o >= start + span.totalLen()) return NOT_IN_LEGACY_SLOT;
        long coreStart = start + span.leadGapLen() + span.fadeLen();
        return (long) worldX - (o - coreStart);
    }

    /**
     * Where {@code worldX} sits along legacy band {@code kind}'s core: {@code 0} at the first core block,
     * {@code 1} one past the last, below 0 in the lead gap / entry fade and above 1 in the exit fade.
     * {@code NaN} outside the band's slot or when it is disabled. Pure.
     */
    public double legacyCoreProgress(LegacyBandKind kind, int worldX) {
        if (layout != null) {
            long l = legacyRunLocal(worldX);
            long cs = eraCoreStart(kind);
            if (l < 0L || cs < 0L) return Double.NaN;
            long f = layout.legacyFade();
            long len = legacyLen(kind);
            if (l < cs - f || l >= cs + len + f) return Double.NaN;
            return (double) (l - cs) / len;
        }
        LegacySpan span = spanOf(kind);
        if (span == null) return Double.NaN;
        long o = offset(worldX);
        if (o < 0L) return Double.NaN;
        long start = legacySlotStart(kind);
        if (o < start || o >= start + span.totalLen()) return Double.NaN;
        long holdStart = start + span.leadGapLen() + span.fadeLen();
        return (double) (o - holdStart) / span.holdLen();
    }

    /**
     * True if {@code worldX} lies anywhere from the start of legacy band {@code kind}'s lead gap through
     * the end of its exit fade — the world has not settled back into plain overworld yet. Used by the
     * {@code reached_overworld_again} gate so "Re-Over-World" waits for the overworld after the LAST band.
     */
    public boolean isInLegacyApproachOrBand(LegacyBandKind kind, int worldX) {
        if (layout != null) return layout.eraIndex(kind) >= 0 && layoutApproachOrBand(CycleLayout.Type.LEGACY_RUN, worldX);
        LegacySpan span = spanOf(kind);
        if (span == null) return false;
        long o = offset(worldX);
        if (o < 0L) return false;
        long start = legacySlotStart(kind);
        return o >= start && o < start + span.totalLen();
    }
}
