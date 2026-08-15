package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
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
     * void/core, {@code middleRamp == 1}) AND none of them fall in the upside-down band's entry
     * lead-in zone (where {@code WorldDisintegrationEvents} stops eroding so real terrain survives as
     * mirror source material — real generation must run there instead of this empty-chunk fast path).
     * Routed through {@link WorldGenCycle} so it matches the combined nether+End+upside-down layout:
     * nether and overworld columns read 0, so those chunks are never skipped. False when
     * disintegration is off.
     */
    public static boolean isChunkFullyEroded(ServerLevel overworld, int chunkMinX) {
        if (startX(overworld) == OFF) return false;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        for (int dx = 0; dx < 16; dx++) {
            int worldX = chunkMinX + dx;
            if (cycle.endMiddleRamp(worldX) < 1.0) return false;
            if (cycle.isInUpsideDownEntryLead(worldX)) return false;
        }
        return true;
    }

    /**
     * True iff the block at {@code (blockX, blockY, blockZ)} is <b>certainly</b> empty void — the
     * erosion pass removed it with probability 1, so no noise roll can have spared it. The per-block
     * counterpart to {@link #isChunkFullyEroded}, and the disintegration-band analogue of
     * {@link ChuncksBand#isVoidSpace}.
     *
     * <p>Deliberately <i>conservative</i>: it answers "definitely void", never "probably void". The
     * whole-chunk test above covers only the band core, which leaves the 120-block fade zones — the
     * one part of the band that still holds terrain, and therefore oceans, lakes and aquifers —
     * completely unguarded. But a fade column is not uniformly eroded: removal probability is
     * {@code ramp × (1 + depthBoost)} with {@code depthBoost} growing to 1 over
     * {@link Disintegration#VERTICAL_SPAN} blocks below the bed, so any column with
     * {@code ramp ≥ 0.5} is 100% removed from ~96 blocks under the bed downward while its surface
     * stays patchy. This returns true for exactly that certain part.</p>
     *
     * <p>Because {@code p ≥ 1} implies the block is air (or a preserved structure block, where a
     * fluid veto is a no-op vanilla would perform anyway), the test cannot misfire onto real terrain.
     * The two carve-outs {@code WorldDisintegrationEvents} applies are mirrored so it stays an exact
     * subset of what erosion actually removed:</p>
     * <ul>
     *   <li>the upside-down band's entry lead-in, where erosion is suppressed so real terrain
     *       survives as mirror source material;</li>
     *   <li>the preserved corridor footprint in the fully-eroded core (the tunnel wall-to-wall Z
     *       span, plus the bed and rail rows inside the track corridor), which erosion keeps intact
     *       so the train has something to ride on.</li>
     * </ul>
     *
     * <p>Gates are ordered cheapest-first — pure geometry before the {@code DungeonTrainWorldData}
     * lookup — the same way {@link ChuncksBand#kindOf} does, so out-of-band callers fall out in a
     * few comparisons. False when disintegration is off.</p>
     */
    public static boolean isVoidSpace(ServerLevel overworld, int blockX, int blockY, int blockZ) {
        long startX = startX(overworld);
        if (startX == OFF) return false;
        if (blockX < startX) return false;                       // before the first band

        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.isInUpsideDownEntryLead(blockX)) return false;  // erosion suppressed here
        double ramp = cycle.endMiddleRamp(blockX);
        if (ramp <= 0.0) return false;                            // overworld / nether column

        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        return certainVoid(ramp, blockY, blockZ, TrackGeometry.from(data.dims(), data.getTrainY()));
    }

    /**
     * Pure core of {@link #isVoidSpace} — "did erosion remove this block with certainty?", given an
     * already-resolved column ramp and the train's track geometry. Split out so it is unit-testable
     * without a NeoForge bootstrap, the same way {@link ChuncksBand#classify} and
     * {@link ChuncksBand#cutY} are.
     *
     * <p>Mirrors the two carve-outs {@code WorldDisintegrationEvents} applies, so the answer stays a
     * strict subset of what erosion actually removed: the preserved tunnel footprint in the
     * fully-eroded core, and the bed/rail rows inside the track corridor.</p>
     */
    static boolean certainVoid(double ramp, int blockY, int blockZ, TrackGeometry g) {
        if (ramp <= 0.0) return false;
        int bedY = g.bedY();
        if (Disintegration.removalProbabilityFromRamp(ramp, blockY, bedY) < 1.0) return false;

        if (ramp >= 1.0) {
            TunnelGeometry tg = TunnelGeometry.from(g);
            if (blockZ >= tg.wallMinZ() && blockZ <= tg.wallMaxZ()) return false;
        }
        return !(blockZ >= g.trackZMin() && blockZ <= g.trackZMax()
                && (blockY == bedY || blockY == g.railY()));
    }
}
