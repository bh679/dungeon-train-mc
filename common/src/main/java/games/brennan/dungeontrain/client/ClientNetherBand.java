package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;

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
        return WorldGenCycle.fromConfig().netherRamp((int) Math.floor(worldX));
    }

    /** Nether intensity {@code n} crosses this point: below it the Overworld track plays, above it the Nether track. */
    public static final double MUSIC_CROSSOVER = 0.5;

    /**
     * Position-driven music volume factor in {@code [0, 1]} at a world-X — a V-curve over the
     * band's nether intensity {@code n}: {@code 1} in the Overworld ({@code n=0}) and across the
     * real-Nether core ({@code n=1}), dipping to {@code 0} at the {@link #MUSIC_CROSSOVER}
     * ({@code n=0.5}).
     *
     * <p>Combined with the track flip at the crossover, this fades the outgoing track down to
     * silence as the player approaches the handoff and the incoming track back up afterwards —
     * Overworld→Nether on the way in, Nether→Overworld on the way out. {@code 1.0} outside any
     * nether band (or when the band is disabled / this world has no train, since
     * {@link #netherIntensityAt} already gates on both), so scaling music volume by it is a no-op
     * away from the transition. Mirrors {@link ClientVoidBand#musicVolumeFactor}.</p>
     */
    public static double musicVolumeFactor(double worldX) {
        double n = netherIntensityAt(worldX);
        if (n <= 0.0) return 1.0;
        return Math.abs(2.0 * n - 1.0);
    }
}
