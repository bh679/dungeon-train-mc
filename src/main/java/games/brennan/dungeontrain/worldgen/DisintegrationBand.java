package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-side helper for the disintegration band. The band is a cycle —
 * overworld → void → End islands → void → overworld — that repeats forever along
 * +X starting at {@link #startX(ServerLevel)}; the per-cycle ramps live in
 * {@link Disintegration}. Shared by the worldgen feature, the erosion handler,
 * {@code BedrockFloorEvents}, and the band mob-spawn rule.
 */
public final class DisintegrationBand {

    /** Returned by {@link #startX} when disintegration is disabled or the world has no train. */
    public static final long OFF = Long.MAX_VALUE;

    private DisintegrationBand() {}

    /**
     * World-X where the very first band begins (and the repeating cycle is anchored),
     * or {@link #OFF} if disintegration is disabled or this world has no train. Past
     * this X the cycle repeats forever; before it is plain overworld.
     */
    public static long startX(ServerLevel overworld) {
        if (!DungeonTrainCommonConfig.isDisintegrationEnabled()) return OFF;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return OFF;
        if (Disintegration.cyclePeriod(
                DungeonTrainCommonConfig.getDisintegrationFadeBlocks(),
                DungeonTrainCommonConfig.getDisintegrationVoidHoldBlocks(),
                DungeonTrainCommonConfig.getDisintegrationEndHoldBlocks(),
                DungeonTrainCommonConfig.getDisintegrationOverworldHoldBlocks()) <= 0L) {
            return OFF;
        }
        return DungeonTrainCommonConfig.getDisintegrationStartBlocks();
    }

    /**
     * Middle ramp (erosion / End-sky intensity) at a single world-X for this overworld,
     * reading the live config spans. 0 in overworld stretches and before the band.
     */
    public static double middleRampAt(ServerLevel overworld, int worldX) {
        long sx = startX(overworld);
        if (sx == OFF) return 0.0;
        return Disintegration.middleRamp(worldX, sx,
                DungeonTrainCommonConfig.getDisintegrationPhaseShiftBlocks(),
                DungeonTrainCommonConfig.getDisintegrationFadeBlocks(),
                DungeonTrainCommonConfig.getDisintegrationVoidHoldBlocks(),
                DungeonTrainCommonConfig.getDisintegrationEndHoldBlocks(),
                DungeonTrainCommonConfig.getDisintegrationOverworldHoldBlocks());
    }

    /**
     * End ramp (End-island fill intensity) at a single world-X for this overworld, reading the
     * live config spans. 0 in the void holds, the overworld stretches, and before the band.
     */
    public static double endRampAt(ServerLevel overworld, int worldX) {
        long sx = startX(overworld);
        if (sx == OFF) return 0.0;
        return Disintegration.endRamp(worldX, sx,
                DungeonTrainCommonConfig.getDisintegrationPhaseShiftBlocks(),
                DungeonTrainCommonConfig.getDisintegrationFadeBlocks(),
                DungeonTrainCommonConfig.getDisintegrationVoidHoldBlocks(),
                DungeonTrainCommonConfig.getDisintegrationEndHoldBlocks(),
                DungeonTrainCommonConfig.getDisintegrationOverworldHoldBlocks());
    }

    /**
     * Which band ({@link Disintegration.Zone}) the column at {@code worldX} sits in for this
     * overworld. Returns {@link Disintegration.Zone#OVERWORLD} when disintegration is off (both
     * ramps are 0). Drives the reach-the-void / End-islands / overworld-again advancements.
     */
    public static Disintegration.Zone zoneAt(ServerLevel overworld, int worldX) {
        return Disintegration.zoneOf(middleRampAt(overworld, worldX), endRampAt(overworld, worldX));
    }
}
