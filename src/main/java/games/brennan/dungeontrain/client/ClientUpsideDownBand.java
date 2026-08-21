package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Client-side cache for the <b>upside-down band</b>'s atmosphere, mirroring {@link ClientNetherBand}.
 * The band start is a raw block count in COMMON config (not a carriage metric), so the only per-world
 * fact that must be synced is whether this world runs the train system — reused from the
 * {@code VoidBandSyncPacket} that the End band already sends on join (no new packet). The enabled flag
 * and spans are COMMON config, readable directly on the client, so config tuning takes effect without
 * a resync.
 *
 * <p>Pure-logic only (no rendering imports); the ramp math is shared with the server via
 * {@link WorldGenCycle#upsideDownRamp}, and the lid height with {@link UpsideDownBand#roofY} via the
 * same two pure helpers the server calls.</p>
 */
public final class ClientUpsideDownBand {

    private static volatile boolean startsWithTrain = false;
    private static volatile int trainY = 0;
    private static volatile int bedrockY = Integer.MIN_VALUE;

    private ClientUpsideDownBand() {}

    /**
     * Apply a server sync: whether this world has the train system, the carriage height, and the
     * world's terrain floor (which lives on the chunk generator, so the client cannot work it out).
     */
    public static void update(boolean starts, int trainYIn, int bedrockYIn) {
        startsWithTrain = starts;
        trainY = trainYIn;
        bedrockY = bedrockYIn;
    }

    /** Reset on disconnect so a band never leaks into the next world. */
    public static void reset() {
        startsWithTrain = false;
        trainY = 0;
        bedrockY = Integer.MIN_VALUE;
    }

    /**
     * Upside-down atmosphere intensity {@code t} in {@code [0,1]} at a world-X: 0 outside the band
     * (or when the band is disabled / this world has no train), ramping to 1 across the mirrored core.
     * Drives the horizontally-rotating sky/fog crossfade and the bright side-lit lightmap. Repeats
     * every cycle along +X.
     */
    public static double upsideDownIntensityAt(double worldX) {
        if (!startsWithTrain) return 0.0;
        if (!DungeonTrainCommonConfig.isUpsideDownEnabled()) return 0.0;
        return WorldGenCycle.fromConfig().upsideDownRamp((int) Math.floor(worldX));
    }

    /**
     * Band membership at a world-X, <b>including the entry lead-in zone</b> — the client mirror of the
     * server {@link games.brennan.dungeontrain.worldgen.UpsideDownBand#isInBandOrEntryLead}. Gates the
     * block-render flip ({@code ModelBlockRendererUpsideDownMixin}): every block whose world-X is in the
     * band or its lead-in renders upside down, so the terrain the mirror reveals during the Y-window
     * fade is visually inverted too (not left upright). 0-cost when the band is off / no train.
     */
    public static boolean isInBand(int worldX) {
        if (!startsWithTrain) return false;
        if (!DungeonTrainCommonConfig.isUpsideDownEnabled()) return false;
        return WorldGenCycle.fromConfig().isInUpsideDownBandOrEntryLead(worldX);
    }

    /**
     * World-Y of the upside-down mirror plane ({@code trainY + mirrorPlaneOffset}) — the split line for
     * the exit-crossfade render flip. Blocks at/above it are the reflected ceiling (flip); below it is the
     * returning overworld / open gap (leave upright). The offset is COMMON config, readable on the client.
     */
    public static int plane() {
        return trainY + DungeonTrainCommonConfig.getUpsideDownMirrorPlaneOffset();
    }

    /**
     * World-Y of the in-band inverted bedrock lid — the client's copy of
     * {@code UpsideDownBand.roofY}, from the synced {@code trainY} and {@code bedrockY} plus COMMON
     * config. {@link Integer#MAX_VALUE} before a sync has landed, so nothing is treated as attic.
     */
    private static int roofY() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || bedrockY == Integer.MIN_VALUE) return Integer.MAX_VALUE;
        int mirror = plane();
        int ceilingGap = Math.max(0, DungeonTrainCommonConfig.getUpsideDownCeilingGap());
        int roofY = UpsideDownBand.bedrockRoofY(
            mirror, ceilingGap, bedrockY, level.getMaxBuildHeight());
        return UpsideDownBand.cappedRoofY(roofY, mirror, ceilingGap,
            DungeonTrainCommonConfig.getUpsideDownMaxCeilingHeight());
    }

    /**
     * True for a block in <b>twin space</b> — the sealed world outside the terrain that the portal
     * system stamps its twin corridors and rooms into: under the terrain floor, or over the band's
     * inverted bedrock lid.
     *
     * <p><b>Why the render flip must skip it.</b> A twin corridor is block-identical to the carriage
     * it stands in for, and that is the whole trick: a player crossing the midpoint is teleported
     * between the two and cannot tell. The band flips every world block but deliberately excludes the
     * train (Sable sub-levels, see {@code ModelBlockRendererUpsideDownMixin}) — so a flipped twin
     * against an upright carriage would make the swap plainly visible. The rooms are hidden inside a
     * sealed shell either way, so nothing is lost by leaving that space upright.</p>
     *
     * <p>Both ends are tested, not just the one the band would stamp into today: a twin stamped in
     * the basement outlives the train's crossing into the band, and would flip in the meantime.</p>
     */
    public static boolean isInTwinSpace(int y) {
        if (bedrockY == Integer.MIN_VALUE) return false;
        return y < bedrockY || y > roofY();
    }

    /**
     * True if {@code worldX} lies in the upside-down → overworld exit crossfade, where the block render
     * flip is applied with a Y-split (only at/above {@link #plane()}) — so the dispersing mirror islands
     * stay visually upside-down while the returning overworld renders upright. Distinct from
     * {@link #isInBand} (the full flip zone). 0-cost when off / no train.
     */
    public static boolean isInExitFlip(int worldX) {
        if (!startsWithTrain) return false;
        if (!DungeonTrainCommonConfig.isUpsideDownEnabled()) return false;
        return WorldGenCycle.fromConfig().isInUpsideDownExitFade(worldX);
    }
}
