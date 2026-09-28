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
     * has no train. Past this X the cycle repeats forever; behind it (after an overworld buffer) it runs in reverse.
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
        return middleRampAt(overworld, worldX, MixBand.NO_Z);
    }

    /** {@link #middleRampAt(ServerLevel, int)} for the chunk holding {@code (worldX, worldZ)} — its mix-zone pick included. */
    public static double middleRampAt(ServerLevel overworld, int worldX, int worldZ) {
        if (startX(overworld) == OFF) return 0.0;
        return MixBand.cycleAtColumn(overworld, worldX, worldZ).endMiddleRamp(worldX);
    }

    /**
     * End-island fill ramp (End-island fill intensity) at a single world-X; 0 in the void holds,
     * the overworld/nether stretches, and before the band. Routed through {@link WorldGenCycle} so
     * the End band sits in the same place the combined nether+End layout positions it.
     */
    public static double endIslandRampAt(ServerLevel overworld, int worldX) {
        return endIslandRampAt(overworld, worldX, MixBand.NO_Z);
    }

    /** {@link #endIslandRampAt(ServerLevel, int)} for the chunk holding {@code (worldX, worldZ)} — its mix-zone pick included. */
    public static double endIslandRampAt(ServerLevel overworld, int worldX, int worldZ) {
        if (startX(overworld) == OFF) return 0.0;
        return MixBand.cycleAtColumn(overworld, worldX, worldZ).endIslandRamp(worldX);
    }

    /**
     * Which band ({@link Disintegration.Zone}) the column at {@code worldX} sits in for this
     * overworld. Returns {@link Disintegration.Zone#OVERWORLD} when disintegration is off (both
     * ramps are 0). Drives the reach-the-void / End-islands / overworld-again advancements.
     */
    public static Disintegration.Zone zoneAt(ServerLevel overworld, int worldX) {
        return zoneAt(overworld, worldX, MixBand.NO_Z);
    }

    /** {@link #zoneAt(ServerLevel, int)} for the chunk holding {@code (worldX, worldZ)} — its mix-zone pick included. */
    public static Disintegration.Zone zoneAt(ServerLevel overworld, int worldX, int worldZ) {
        return Disintegration.zoneOf(middleRampAt(overworld, worldX, worldZ), endIslandRampAt(overworld, worldX, worldZ));
    }

    /**
     * Which repeat of the world-gen cycle (0-based) {@code worldX} falls in, or {@code -1} before the
     * anchor / when disintegration is disabled or this world has no train. Repeat {@code 0} is the first
     * pass through every band; {@code >= 1} means the whole first cycle (Nether, End, upside-down, chuncks)
     * is behind the column. Drives the "Re-Over-World" advancement gate in
     * {@link games.brennan.dungeontrain.event.ZoneProgressEvents}: positional (not advancement-based)
     * so a returning player's cross-world achievement sidecar — which re-grants {@code reached_void} on
     * login — can't satisfy it from the spawn overworld. Mirrors {@link NetherBand#netherPassIndex}.
     */
    public static long cyclePassIndex(ServerLevel overworld, int worldX) {
        return cyclePassIndex(overworld, worldX, MixBand.NO_Z);
    }

    /** {@link #cyclePassIndex(ServerLevel, int)} for the chunk holding {@code (worldX, worldZ)} — its mix-zone pick included. */
    public static long cyclePassIndex(ServerLevel overworld, int worldX, int worldZ) {
        if (startX(overworld) == OFF) return -1L;
        return MixBand.cycleAtColumn(overworld, worldX, worldZ).cycleIndex(worldX);
    }

    /**
     * True iff every column of the 16-wide chunk at {@code chunkMinX} is fully eroded (the End
     * void/core, {@code middleRamp == 1}) AND none of them fall in the upside-down band's entry
     * lead-in zone (where {@code WorldDisintegrationEvents} stops eroding so real terrain survives as
     * mirror source material — real generation must run there instead of this empty-chunk fast path).
     * Routed through {@link WorldGenCycle} so it matches the combined nether+End+upside-down layout:
     * nether and overworld columns read 0, so those chunks are never skipped. False when
     * disintegration is off.
     */
    public static boolean isChunkFullyEroded(ServerLevel overworld, int chunkMinX) {
        return isChunkFullyEroded(overworld, chunkMinX, MixBand.NO_Z);
    }

    /** {@link #isChunkFullyEroded(ServerLevel, int)} for the chunk at {@code (chunkMinX, chunkMinZ)} — its mix-zone pick included. */
    public static boolean isChunkFullyEroded(ServerLevel overworld, int chunkMinX, int chunkMinZ) {
        if (startX(overworld) == OFF) return false;
        WorldGenCycle cycle = MixBand.cycleAtColumn(overworld, chunkMinX, chunkMinZ);
        for (int dx = 0; dx < 16; dx++) {
            int worldX = chunkMinX + dx;
            if (cycle.endMiddleRamp(worldX) < 1.0) return false;
            if (cycle.isInUpsideDownEntryLead(worldX)) return false;
        }
        return true;
    }
}
