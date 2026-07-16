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
     * Which repeat of the world-gen cycle (0-based) the Nether band at {@code worldX} belongs to, or
     * {@code -1} before the cycle / when the nether phase is disabled or this world has no train. The
     * Nether band is the first special band of every period, so pass index 0 is the FIRST Nether band,
     * 1 the second, and so on. Drives the "Nether Return Again" advancement, which fires only from the
     * second Nether band onward (index ≥ 1) — see
     * {@link games.brennan.dungeontrain.event.ZoneProgressEvents}. Positional (not advancement-based)
     * so a returning player's cross-world sidecar can't fire the return on the first pass.
     */
    public static long netherPassIndex(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return -1L;
        return WorldGenCycle.fromConfig().cycleIndex(worldX);
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

    /**
     * True when world-X sits anywhere in the band's <b>netherrack stretch</b> — the netherrack
     * crossfade-in, the core, and the crossfade-out (i.e. {@code netherRamp > 0}), End band not
     * owning the column. This is the span where the world <em>reads</em> as the Nether (netherrack
     * present), which begins well before the strict {@link #isInNetherBiome biome core}
     * ({@code netherRamp >= 0.5}, reached only ~{@code coreFade/2} blocks deeper).
     *
     * <p>The train-<b>phase</b> gate ({@link games.brennan.dungeontrain.worldgen.TrainPhase#phaseAt})
     * uses this so a NETHER-gated template / Stage spawns across the visible Nether stretch instead of
     * only its deep core — fixing "Nether stages don't trigger until deep into the Nether". The strict
     * {@link #isInNetherBiome} stays the source of truth for the real Nether biome / mob behaviour.</p>
     */
    public static boolean isInNetherBand(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return false;
        if (DisintegrationBand.middleRampAt(overworld, worldX) > 0.0) return false; // End band wins
        return netherRampAt(overworld, worldX) > 0.0;
    }

    /** Pure {@link WorldGenCycle} overload of {@link #isInNetherBand(ServerLevel, int)} (End wins, then
     *  {@code netherRamp > 0}) — for tests and any hot per-column caller that hoisted the cycle. */
    public static boolean isInNetherBand(WorldGenCycle cycle, int worldX) {
        if (cycle.endMiddleRamp(worldX) > 0.0) return false; // End band wins
        return cycle.netherRamp(worldX) > 0.0;
    }
}
