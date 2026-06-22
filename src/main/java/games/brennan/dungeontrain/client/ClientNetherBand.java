package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.worldgen.NetherTransition;

/**
 * Client-side cache for the <b>Nether transition band</b>'s atmosphere, mirroring
 * {@link ClientVoidBand}. The band start is a raw block count in COMMON config (not a
 * carriage metric), so the only per-world fact that must be synced is whether this
 * world runs the train system — reused from the {@code VoidBandSyncPacket} that the
 * End band already sends on join (no new packet). The enabled flag and spans are COMMON
 * config, readable directly on the client, so config tuning takes effect without a resync.
 *
 * <p>Pure-logic only (no rendering imports); the band math is shared with the server via
 * {@link NetherTransition}.</p>
 */
public final class ClientNetherBand {

    private static volatile boolean startsWithTrain = false;

    private ClientNetherBand() {}

    /** Apply a server sync (whether this world has the train system). */
    public static void update(boolean starts) {
        startsWithTrain = starts;
    }

    /** Reset on disconnect so a band never leaks into the next world. */
    public static void reset() {
        startsWithTrain = false;
    }

    /**
     * Nether intensity {@code n} in {@code [0,1]} at a world-X: 0 outside a nether core
     * (or when the band is disabled / this world has no train), ramping to 1 across the
     * real-Nether core. Drives the red fog blend and cloud suppression. Repeats every
     * cycle along +X.
     */
    public static double netherIntensityAt(double worldX) {
        if (!startsWithTrain) return 0.0;
        if (!DungeonTrainCommonConfig.isNetherTransitionEnabled()) return 0.0;
        long startX = DungeonTrainCommonConfig.getNetherStartBlocks();
        int fade = DungeonTrainCommonConfig.getNetherFadeBlocks();
        int mtnHold = DungeonTrainCommonConfig.getNetherMountainHoldBlocks();
        int coreFade = DungeonTrainCommonConfig.getNetherCoreFadeBlocks();
        int coreHold = DungeonTrainCommonConfig.getNetherCoreHoldBlocks();
        int owHold = DungeonTrainCommonConfig.getNetherOverworldHoldBlocks();
        return NetherTransition.netherRamp((int) Math.floor(worldX), startX, fade, mtnHold, coreFade, coreHold, owHold);
    }
}
