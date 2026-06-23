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
     * World-X where the cycle is anchored (shared with the nether phase via
     * {@link WorldGenCycle}), or {@link #OFF} if disintegration is disabled or this world
     * has no train. Past this X the cycle repeats forever; before it is plain overworld.
     */
    public static long startX(ServerLevel overworld) {
        if (!DungeonTrainCommonConfig.isDisintegrationEnabled()) return OFF;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return OFF;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.period() <= 0L || cycle.endLen() <= 0L) return OFF;
        return cycle.startX();
    }

    /**
     * Middle ramp (erosion / End-sky intensity) at a single world-X for this overworld.
     * 0 in overworld stretches, before the cycle, and across the nether segment.
     */
    public static double middleRampAt(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return 0.0;
        return WorldGenCycle.fromConfig().endMiddleRamp(worldX);
    }

    /**
     * End-island fill ramp (End-island fill intensity) at a single world-X; 0 in the void holds,
     * the overworld/nether stretches, and before the band. Routed through {@link WorldGenCycle} so
     * the End band sits in the same place the combined nether+End layout positions it.
     */
    public static double endIslandRampAt(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return 0.0;
        return WorldGenCycle.fromConfig().endIslandRamp(worldX);
    }

    /**
     * Which band ({@link Disintegration.Zone}) the column at {@code worldX} sits in for this
     * overworld. Returns {@link Disintegration.Zone#OVERWORLD} when disintegration is off (both
     * ramps are 0). Drives the reach-the-void / End-islands / overworld-again advancements.
     */
    public static Disintegration.Zone zoneAt(ServerLevel overworld, int worldX) {
        return Disintegration.zoneOf(middleRampAt(overworld, worldX), endIslandRampAt(overworld, worldX));
    }

    /**
     * True iff every column of the 16-wide chunk at {@code chunkMinX} is fully eroded (the End
     * void/core, {@code middleRamp == 1}) — the empty-chunk fast path for the worldgen mixins.
     * Routed through {@link WorldGenCycle} so it matches the combined nether+End layout: nether and
     * overworld columns read 0, so those chunks are never skipped. False when disintegration is off.
     */
    public static boolean isChunkFullyEroded(ServerLevel overworld, int chunkMinX) {
        if (startX(overworld) == OFF) return false;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        for (int dx = 0; dx < 16; dx++) {
            if (cycle.endMiddleRamp(chunkMinX + dx) < 1.0) return false;
        }
        return true;
    }
}
