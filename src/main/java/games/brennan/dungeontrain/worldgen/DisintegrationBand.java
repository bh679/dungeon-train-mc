package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-side helper that resolves the disintegration band's world-X range for a
 * world, combining the per-world carriage length ({@link DungeonTrainWorldData})
 * with the COMMON config spans. Shared by the worldgen feature that carves the band
 * and by {@code BedrockFloorEvents} (which suppresses the bedrock floor in-band).
 */
public final class DisintegrationBand {

    private DisintegrationBand() {}

    /**
     * Band X-range {@code [startX, endX)} for this overworld, or {@code null} if
     * disintegration is disabled or the world has no train.
     */
    public static long[] range(ServerLevel overworld) {
        if (!DungeonTrainCommonConfig.isDisintegrationEnabled()) return null;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return null;
        int length = data.dims().length();
        long startX = Disintegration.bandStartX(
                DungeonTrainCommonConfig.getDisintegrationStartCarriages(), length);
        long bandLen = Disintegration.bandLength(
                DungeonTrainCommonConfig.getDisintegrationFadeBlocks(),
                DungeonTrainCommonConfig.getDisintegrationVoidHoldBlocks(),
                DungeonTrainCommonConfig.getDisintegrationEndHoldBlocks());
        if (bandLen <= 0) return null;
        return new long[] { startX, startX + bandLen };
    }
}
