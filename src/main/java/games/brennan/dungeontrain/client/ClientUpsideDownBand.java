package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;

/**
 * Client-side cache for the <b>upside-down band</b>'s atmosphere, mirroring {@link ClientNetherBand}.
 * The band start is a raw block count in COMMON config (not a carriage metric), so the only per-world
 * fact that must be synced is whether this world runs the train system — reused from the
 * {@code VoidBandSyncPacket} that the End band already sends on join (no new packet). The enabled flag
 * and spans are COMMON config, readable directly on the client, so config tuning takes effect without
 * a resync.
 *
 * <p>Pure-logic only (no rendering imports); the ramp math is shared with the server via
 * {@link WorldGenCycle#upsideDownRamp}.</p>
 */
public final class ClientUpsideDownBand {

    private static volatile boolean startsWithTrain = false;

    private ClientUpsideDownBand() {}

    /** Apply a server sync (whether this world has the train system). */
    public static void update(boolean starts) {
        startsWithTrain = starts;
    }

    /** Reset on disconnect so a band never leaks into the next world. */
    public static void reset() {
        startsWithTrain = false;
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
}
