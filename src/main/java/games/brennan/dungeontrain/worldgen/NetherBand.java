package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-side adapter for the <b>Nether transition phase</b> of the single repeating
 * {@link WorldGenCycle}. The cycle (OW → Nether → OW → End → repeat) is anchored at the
 * disintegration start and uses the disintegration overworld hold as its gap; this
 * helper just gates on the per-world train flag + the nether toggle and exposes the
 * nether segment's two ramps to the worldgen feature, chunk-load cleanup and mob spawner.
 */
public final class NetherBand {

    /** Returned by {@link #startX} when the nether phase is disabled or the world has no train. */
    public static final long OFF = Long.MAX_VALUE;

    private NetherBand() {}

    /**
     * World-X where the cycle is anchored (shared with the End band), or {@link #OFF} if
     * the nether phase is disabled or this world has no train.
     */
    public static long startX(ServerLevel overworld) {
        if (!DungeonTrainCommonConfig.isNetherTransitionEnabled()) return OFF;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return OFF;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.period() <= 0L || cycle.netherLen() <= 0L) return OFF;
        return cycle.startX();
    }

    /** Mountain-height ramp at a single world-X; 0 outside the nether segment / when disabled. */
    public static double heightRampAt(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return 0.0;
        return WorldGenCycle.fromConfig().netherHeightRamp(worldX);
    }

    /** Nether intensity ramp (netherrack → real Nether) at a single world-X; 0 outside the core. */
    public static double netherRampAt(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return 0.0;
        return WorldGenCycle.fromConfig().netherRamp(worldX);
    }
}
