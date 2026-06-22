package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.worldgen.Disintegration;

/**
 * Client-side cache of the per-world data needed to evaluate the disintegration
 * band's "void intensity" at a world-X, so the sky/fog can fade toward the End
 * look as the player crosses the band.
 *
 * <p>The band start-X depends on the per-world carriage length (server-only
 * {@code DungeonTrainWorldData.dims()}), so that single value plus the per-world
 * {@code startsWithTrain} flag are synced once on join via {@code VoidBandSyncPacket}.
 * Everything else — the enabled flag and the fade/core spans — is COMMON config,
 * readable directly on the client, so config tuning takes effect without a resync.</p>
 *
 * <p>Pure-logic only (no rendering imports); the band math is shared with the
 * server via {@link Disintegration}.</p>
 */
public final class ClientVoidBand {

    private static volatile int carriageLength = CarriageDims.DEFAULT_LENGTH;
    private static volatile boolean startsWithTrain = false;

    private ClientVoidBand() {}

    /** Apply a server sync (carriage length + whether this world has the train system). */
    public static void update(int length, boolean starts) {
        carriageLength = Math.max(1, length);
        startsWithTrain = starts;
    }

    /** Reset to safe defaults on disconnect so a band never leaks into the next world. */
    public static void reset() {
        carriageLength = CarriageDims.DEFAULT_LENGTH;
        startsWithTrain = false;
    }

    /**
     * End-sky intensity {@code t} in {@code [0, 1]} at a world-X: 0 outside a band
     * phase (or when disintegration is disabled / this world has no train), ramping to
     * 1 across the whole void+End+void middle. Repeats every cycle, so the End
     * atmosphere returns each time the world breaks apart. Drives the sky crossfade,
     * the fog darkening, and cloud suppression.
     *
     * <p>Uses {@link Disintegration#skyRamp} (not {@code middleRamp}), so the sky lags the
     * terrain by {@code disintegrationSkyFadeOffsetBlocks} on each edge: it stays overworld
     * while the ground first crumbles on entry and returns to overworld before the terrain
     * has fully reformed on exit. Offset 0 reproduces the terrain-synced behaviour.</p>
     */
    public static double endSkyIntensityAt(double worldX) {
        if (!startsWithTrain) return 0.0;
        if (!DungeonTrainCommonConfig.isDisintegrationEnabled()) return 0.0;
        long startX = DungeonTrainCommonConfig.getDisintegrationStartBlocks();
        int phaseShift = DungeonTrainCommonConfig.getDisintegrationPhaseShiftBlocks();
        int fade = DungeonTrainCommonConfig.getDisintegrationFadeBlocks();
        int voidHold = DungeonTrainCommonConfig.getDisintegrationVoidHoldBlocks();
        int endHold = DungeonTrainCommonConfig.getDisintegrationEndHoldBlocks();
        int owHold = DungeonTrainCommonConfig.getDisintegrationOverworldHoldBlocks();
        int skyOffset = DungeonTrainCommonConfig.getDisintegrationSkyFadeOffsetBlocks();
        return Disintegration.skyRamp((int) Math.floor(worldX), startX, phaseShift, fade, voidHold, endHold, owHold, skyOffset);
    }
}
