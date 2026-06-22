package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-side helper for the <b>Nether transition band</b> — a second, independent
 * looping phase (overworld → world-height mega-mountain the train tunnels through →
 * real Nether → overworld) that repeats forever along +X starting at
 * {@link #startX(ServerLevel)}. The per-cycle ramps live in {@link NetherTransition}.
 * Shared by the worldgen feature, the chunk-load cleanup handler, the mob spawner and
 * the client atmosphere.
 *
 * <p>Independent of the {@link DisintegrationBand} End band: its enable flag and spans
 * are separate COMMON config knobs. Where the two bands would overlap, the End band
 * wins — callers skip nether work for any column where
 * {@link DisintegrationBand#middleRampAt} {@code > 0}.</p>
 */
public final class NetherBand {

    /** Returned by {@link #startX} when the nether band is disabled or the world has no train. */
    public static final long OFF = Long.MAX_VALUE;

    private NetherBand() {}

    /**
     * World-X where the very first nether band begins (and the repeating cycle is
     * anchored), or {@link #OFF} if the band is disabled or this world has no train.
     */
    public static long startX(ServerLevel overworld) {
        if (!DungeonTrainCommonConfig.isNetherTransitionEnabled()) return OFF;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return OFF;
        if (NetherTransition.cyclePeriod(
                DungeonTrainCommonConfig.getNetherFadeBlocks(),
                DungeonTrainCommonConfig.getNetherMountainHoldBlocks(),
                DungeonTrainCommonConfig.getNetherCoreFadeBlocks(),
                DungeonTrainCommonConfig.getNetherCoreHoldBlocks(),
                DungeonTrainCommonConfig.getNetherOverworldHoldBlocks()) <= 0L) {
            return OFF;
        }
        return DungeonTrainCommonConfig.getNetherStartBlocks();
    }

    /** Height ramp (mountain top fraction) at a single world-X for this overworld; 0 outside a band. */
    public static double heightRampAt(ServerLevel overworld, int worldX) {
        long sx = startX(overworld);
        if (sx == OFF) return 0.0;
        return NetherTransition.heightRamp(worldX, sx,
                DungeonTrainCommonConfig.getNetherFadeBlocks(),
                DungeonTrainCommonConfig.getNetherMountainHoldBlocks(),
                DungeonTrainCommonConfig.getNetherCoreFadeBlocks(),
                DungeonTrainCommonConfig.getNetherCoreHoldBlocks(),
                DungeonTrainCommonConfig.getNetherOverworldHoldBlocks());
    }

    /** Nether intensity ramp (netherrack → real Nether) at a single world-X; 0 outside the core. */
    public static double netherRampAt(ServerLevel overworld, int worldX) {
        long sx = startX(overworld);
        if (sx == OFF) return 0.0;
        return NetherTransition.netherRamp(worldX, sx,
                DungeonTrainCommonConfig.getNetherFadeBlocks(),
                DungeonTrainCommonConfig.getNetherMountainHoldBlocks(),
                DungeonTrainCommonConfig.getNetherCoreFadeBlocks(),
                DungeonTrainCommonConfig.getNetherCoreHoldBlocks(),
                DungeonTrainCommonConfig.getNetherOverworldHoldBlocks());
    }
}
