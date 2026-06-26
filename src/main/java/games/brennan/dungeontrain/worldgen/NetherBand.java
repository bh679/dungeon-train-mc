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

    /**
     * Nether intensity at/above which the band reads as the Nether core (netherrack + real
     * Nether). Mirrors {@code NetherMobSpawner.SPAWN_INTENSITY_THRESHOLD} so "where Nether mobs
     * belong" is one shared notion.
     */
    public static final double NETHER_CORE_RAMP = 0.5;

    /**
     * True when world-X sits inside the band's Nether core <b>and</b> the End band doesn't own
     * that column — i.e. the column the player reads as the Nether biome. Used to decide where
     * piglins/hoglins should behave as if in the real Nether (no zombification).
     */
    public static boolean isInNetherBiome(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return false;
        if (DisintegrationBand.middleRampAt(overworld, worldX) > 0.0) return false; // End band wins
        return netherRampAt(overworld, worldX) >= NETHER_CORE_RAMP;
    }

    /**
     * Pure, allocation-free band-core test against a <b>pre-resolved</b> {@link WorldGenCycle} — the
     * exact same predicate as {@link #isInNetherBiome(ServerLevel, int)} (End band wins, then
     * {@code netherRamp >= NETHER_CORE_RAMP}) but with the cycle hoisted by the caller, so a hot
     * per-column loop pays no {@code WorldGenCycle.fromConfig} / {@code DungeonTrainWorldData.get}
     * lookups. The caller MUST have already confirmed {@link #startX} != {@link #OFF} (the
     * nether-enabled + starts-with-train + non-empty-band gate) — this overload does not re-check it.
     */
    public static boolean isInNetherBiome(WorldGenCycle cycle, int worldX) {
        if (cycle.endMiddleRamp(worldX) > 0.0) return false; // End band wins
        return cycle.netherRamp(worldX) >= NETHER_CORE_RAMP;
    }
}
