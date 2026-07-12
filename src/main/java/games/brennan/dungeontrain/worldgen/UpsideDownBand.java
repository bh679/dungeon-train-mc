package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-side helper for the upside-down band — the third looping phase (alongside the nether and
 * disintegration/End bands), positioned after the End band in the shared {@link WorldGenCycle}. Past
 * {@link #startX(ServerLevel)} the cycle repeats forever along +X; before it is plain overworld.
 *
 * <p>Unlike the nether/End bands (which reshape terrain via density/biome hooks), the upside-down
 * band is realised purely as a post-process vertical mirror in {@code WorldUpsideDownEvents}. This
 * class only answers "is this column in the band?" (binary — the mirror is all-or-nothing per
 * column) and exposes the client-atmosphere ramp. Shared by that reflection handler,
 * {@link TrainPhase#phaseAt}, and the band mob-spawn rule.</p>
 */
public final class UpsideDownBand {

    /** Returned by {@link #startX} when the band is disabled or the world has no train. */
    public static final long OFF = Long.MAX_VALUE;

    private UpsideDownBand() {}

    /**
     * World-X where the cycle is anchored (shared with the nether/End phases via
     * {@link WorldGenCycle}), or {@link #OFF} if the upside-down band is disabled or this world has
     * no train. Past this X the cycle repeats forever; before it is plain overworld.
     */
    public static long startX(ServerLevel overworld) {
        if (!DungeonTrainCommonConfig.isUpsideDownEnabled()) return OFF;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return OFF;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.period() <= 0L || cycle.upsideDownLen() <= 0L) return OFF;
        return cycle.startX();
    }

    /**
     * True iff the column at {@code worldX} lies anywhere in the upside-down band (fade edges
     * included). False when the band is off. This binary membership — not {@link #rampAt} — gates the
     * terrain reflection, since a vertical mirror is all-or-nothing per column.
     */
    public static boolean isInBand(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return false;
        return WorldGenCycle.fromConfig().isInUpsideDownBand(worldX);
    }

    /**
     * Upside-down atmosphere ramp {@code 0..1} at a world-X (0 outside the band / when disabled) —
     * drives the sky/light crossfade and the mob-spawn gate on the server side.
     */
    public static double rampAt(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return 0.0;
        return WorldGenCycle.fromConfig().upsideDownRamp(worldX);
    }

    /**
     * True iff the column at {@code worldX} lies in the entry lead-in zone immediately before the
     * band (inside the End band's trailing void hold) — where {@code WorldUpsideDownEvents} runs a
     * noise-gated partial mirror instead of the full in-band reflection. False when the band is off.
     */
    public static boolean isInEntryLead(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return false;
        return WorldGenCycle.fromConfig().isInUpsideDownEntryLead(worldX);
    }

    /**
     * Entry lead-in reveal ramp {@code 0..1} at a world-X (0 outside the lead-in zone / when disabled)
     * — the fraction of the mirrored column's Y-window that should be revealed in
     * {@code WorldUpsideDownEvents}'s partial-mirror pass (see {@link #revealYExtent}).
     */
    public static double entryRevealRamp(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return 0.0;
        return WorldGenCycle.fromConfig().upsideDownEntryRevealRamp(worldX);
    }

    /**
     * True iff the column at {@code worldX} lies in the upside-down band OR its entry lead-in zone —
     * the combined gate for the render-flip, water-freeze, grass-freeze, and flipped-corridor lay, so
     * the lead-in's revealed terrain looks and behaves like the band. False when the band is off.
     */
    public static boolean isInBandOrEntryLead(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return false;
        return WorldGenCycle.fromConfig().isInUpsideDownBandOrEntryLead(worldX);
    }

    /**
     * True iff the column at {@code worldX} lies in the upside-down → overworld exit crossfade — the
     * zone right after the band where {@code WorldUpsideDownEvents} disperses the mirror into shrinking
     * islands while fading the normal overworld back in over the void. False when the band is off.
     */
    public static boolean isInExitFade(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return false;
        return WorldGenCycle.fromConfig().isInUpsideDownExitFade(worldX);
    }

    /** Overworld-reveal ramp {@code 0..1} across the exit crossfade (0 outside / when disabled). */
    public static double exitOwReveal(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return 0.0;
        return WorldGenCycle.fromConfig().upsideDownExitOwRevealRamp(worldX);
    }

    /** Mirror-disperse ramp {@code 1..0} across the exit crossfade (0 outside / when disabled). */
    public static double exitMirrorDisperse(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return 0.0;
        return WorldGenCycle.fromConfig().upsideDownExitMirrorDisperseRamp(worldX);
    }

    /**
     * True once the overworld has coalesced enough that the solid {@code minY} floor should return —
     * {@code exitOwReveal ≥ } {@link DungeonTrainCommonConfig#UPSIDE_DOWN_EXIT_FLOOR_RETURN}. Shared by
     * {@code WorldUpsideDownEvents} (which clears the floor) and {@code BedrockFloorEvents} (which stamps
     * it) so the two ChunkEvent.Load handlers agree per column regardless of ordering.
     */
    public static boolean exitFloorPresent(ServerLevel overworld, int worldX) {
        return exitOwReveal(overworld, worldX) >= DungeonTrainCommonConfig.UPSIDE_DOWN_EXIT_FLOOR_RETURN;
    }

    /**
     * True while the mirror is still dense enough to keep the inverted bedrock roof — {@code
     * exitMirrorDisperse ≥ } {@link DungeonTrainCommonConfig#UPSIDE_DOWN_EXIT_ROOF_RECEDE}. Below it the
     * lid recedes so the exit doesn't end in a bedrock wall.
     */
    public static boolean exitRoofPresent(ServerLevel overworld, int worldX) {
        return exitMirrorDisperse(overworld, worldX) >= DungeonTrainCommonConfig.UPSIDE_DOWN_EXIT_ROOF_RECEDE;
    }

    /**
     * True iff {@code worldX} lies in the upside-down band, its entry lead-in, OR its exit crossfade —
     * the full stretch where {@code TrackBedFeature} must skip the during-gen corridor so
     * {@code WorldUpsideDownEvents} can lay the flipped corridor onto the composited terrain instead.
     * False when the band is off.
     */
    public static boolean isInBandEntryLeadOrExit(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return false;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        return cycle.isInUpsideDownBandOrEntryLead(worldX) || cycle.isInUpsideDownExitFade(worldX);
    }

    /**
     * Half-height (blocks) of the entry lead-in reveal window at a given {@code reveal} fraction — the
     * terrain materialises outward from the train gap as {@code reveal} climbs 0→1. At 0 only the two
     * rows flanking the gap (the reflected ceiling floor at {@code mirror + ceilingGap} and hang top at
     * {@code mirror − floorGap}) are shown; at 1 it reaches the full mirror. Grows by equal block count
     * above and below, scaled to the larger of the two sides so both are fully covered at
     * {@code reveal == 1}:
     * <ul>
     *   <li>up:   {@code roofY − (mirror + ceilingGap)} (inner ceiling edge → the roof lid)</li>
     *   <li>down: {@code (mirror − floorGap) − (minY + 1)} (inner hang edge → just above the floor row)</li>
     * </ul>
     * Pure geometry — shared by {@code WorldUpsideDownEvents} and tests, like {@link #bedrockRoofY}.
     */
    public static int revealYExtent(double reveal, int mirror, int ceilingGap, int floorGap, int roofY, int minY) {
        int upMax = Math.max(0, roofY - (mirror + ceilingGap));
        int downMax = Math.max(0, (mirror - floorGap) - (minY + 1));
        int maxExtent = Math.max(upMax, downMax);
        double r = reveal < 0.0 ? 0.0 : (reveal > 1.0 ? 1.0 : reveal);
        return (int) Math.round(r * maxExtent);
    }

    /**
     * World-Y of the in-band bedrock roof lid (the {@code upsideDownBedrockRoof} inversion): the point
     * the old {@code minY} floor mirrors to, clamped into the build range. {@code mirror} is the
     * reflection plane ({@code trainY + mirrorPlaneOffset}); the reflected ceiling's highest block comes
     * from source {@code minY+1}, so this lid at {@code 2·mirror + ceilingGap − minY} sits one block
     * above it, flush on the ceiling. Pure geometry — shared by {@code WorldUpsideDownEvents} and tests.
     */
    public static int bedrockRoofY(int mirror, int ceilingGap, int minY, int maxY) {
        return Math.min(maxY - 1, 2 * mirror + ceilingGap - minY);
    }

    /**
     * The inverted bedrock lid Y after applying the ceiling-height cap ({@code upsideDownMaxCeilingHeight}):
     * with {@code maxCeilingHeight > 0} the lid drops to one block above the capped ceiling
     * ({@code mirror + ceilingGap + maxCeilingHeight}), so it sits flush on the fixed-thickness slab;
     * {@code 0} leaves the uncapped {@code roofY} unchanged. Never raises the roof. Pure — shared by
     * {@code WorldUpsideDownEvents} and tests.
     */
    public static int cappedRoofY(int roofY, int mirror, int ceilingGap, int maxCeilingHeight) {
        if (maxCeilingHeight <= 0) return roofY;
        return Math.min(roofY, mirror + ceilingGap + maxCeilingHeight + 1);
    }

    // ---- exit-crossfade noise-skip predicates -------------------------------
    // The exit fade samples Disintegration.coherentNoise (∈ [0,1)) up to twice per block, gated on the
    // per-COLUMN ramps exitDisperse/exitReveal. Near the saturated ends of those ramps the gate outcome
    // is fixed for the whole column, so the sample can be skipped. These pure predicates decide that once
    // per column (WorldUpsideDownEvents). eps == 0 → provably output-identical; eps > 0 → a near-identical
    // fidelity/perf tradeoff that also skips columns within eps of saturation.

    /**
     * True iff the exit mirror-disperse gate is guaranteed to KEEP every reflected block in a column with
     * this {@code disperse} ramp — so the per-block {@code coherentNoise} sample can be skipped. The gate
     * drops when {@code coherentNoise(...) >= disperse}; since {@code coherentNoise ∈ [0,1)} it can never
     * reach {@code 1.0}, so at {@code disperse >= 1.0} nothing is ever dropped. {@code eps} widens this to
     * {@code disperse >= 1.0 - eps} (at most an {@code eps} fraction of blocks a sample would have dropped
     * are kept instead — imperceptible at the band edge). {@code eps == 0} is output-identical. Pure.
     */
    public static boolean exitMirrorKeepsAll(double disperse, double eps) {
        return disperse >= 1.0 - eps;
    }

    /**
     * True iff the exit mirror-disperse gate is guaranteed to DROP every reflected block in a column with
     * this {@code disperse} ramp — so the sample can be skipped (the block goes straight to air). The gate
     * drops when {@code coherentNoise(...) >= disperse}; since {@code coherentNoise >= 0}, at
     * {@code disperse <= 0.0} every block drops. {@code eps} widens this to {@code disperse <= eps}.
     * {@code eps == 0} is output-identical. Evaluated only after {@link #exitMirrorKeepsAll}, so the two
     * bands never conflict (guaranteed while {@code eps < 0.5}). Pure.
     */
    public static boolean exitMirrorDropsAll(double disperse, double eps) {
        return disperse <= eps;
    }

    /**
     * True iff the exit overworld-reveal gate is guaranteed to KEEP (place) every original overworld block
     * in a column with this {@code reveal} ramp — so the sample can be skipped. The gate places when
     * {@code coherentNoise(...) >= pRemove}, where {@code pRemove =
     * Disintegration.removalProbabilityFromRamp(1 - reveal, y, bedY)} is {@code 0} only when
     * {@code 1 - reveal <= 0} (i.e. {@code reveal >= 1.0}). Because the depth boost can scale that ramp up
     * to {@code ×(1 + }{@link Disintegration#DEPTH_WEIGHT}{@code )}, the epsilon bound must account for it:
     * skipping keeps {@code pRemove <= eps} at every depth only when
     * {@code (1 - reveal)·(1 + DEPTH_WEIGHT) <= eps} — which is why the LOW-reveal end is never skipped.
     * {@code eps == 0} reduces to the provable {@code reveal >= 1.0}. Pure.
     */
    public static boolean exitOverworldKeepsAll(double reveal, double eps) {
        return (1.0 - reveal) * (1.0 + Disintegration.DEPTH_WEIGHT) <= eps;
    }
}
