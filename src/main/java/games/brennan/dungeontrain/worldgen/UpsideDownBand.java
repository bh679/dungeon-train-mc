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
}
