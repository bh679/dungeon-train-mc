package games.brennan.dungeontrain.worldgen;

/**
 * Which overworld stretches take their look from a third-party worldgen mod. On every <b>odd</b> lap
 * of the {@link WorldGenCycle} (cycle index 1, 3, 5, …) — the same parity the BetterNether and
 * BetterEnd laps use — the overworld gap <b>before</b> the Nether band is William Wythers' Overhauled
 * Overworld and the gap <b>after</b> it is Biomes O' Plenty. Lap 0, even laps and every other stretch
 * stay vanilla. A world created without the train has no DT bands at all, so every stretch is vanilla
 * there — this class is the one gate for that, and every caller (server worldgen and client twins
 * alike) passes the world's train flag so both sides agree.
 *
 * <p>Pure (no Minecraft types) so the lap/gap rules are unit-testable. The two mods are confined in
 * different places because they work differently: BoP adds its own biomes, so it is confined at
 * biome choice ({@code density.OverworldStretchBiomes}); WWOO rewrites vanilla biomes in place, so it
 * is confined at feature placement ({@code VanillaBiomeFeatures}).</p>
 */
public final class SecondLapOverworld {

    /** The look a stretch takes. */
    public enum Stretch { VANILLA, WWOO, BOP }

    private SecondLapOverworld() {}

    /** True for the laps that carry the modded stretches: 1, 3, 5, … (never lap 0 or before the anchor). */
    public static boolean isModdedLap(long lap) {
        return lap > 0L && (lap & 1L) == 1L;
    }

    /**
     * The stretch at this world-X; {@link Stretch#VANILLA} when the cycle is missing or the world was
     * created without the train ({@code worldHasTrain == false}).
     */
    public static Stretch at(WorldGenCycle cycle, boolean worldHasTrain, int worldX) {
        if (cycle == null || !worldHasTrain) return Stretch.VANILLA;
        if (cycle.hasLayout()) {
            // Ordered layout: the gap's own style label says which mod owns it (lap 2's WWOO / BoP slots).
            CycleLayout.Style style = cycle.overworldStyleAt(worldX);
            if (style == CycleLayout.Style.WWOO) return Stretch.WWOO;
            if (style == CycleLayout.Style.BOP) return Stretch.BOP;
            return Stretch.VANILLA;
        }
        WorldGenCycle.OverworldGap gap = cycle.overworldGapAt(worldX);
        if (gap == WorldGenCycle.OverworldGap.NONE) return Stretch.VANILLA;
        if (!isModdedLap(cycle.cycleIndex(worldX))) return Stretch.VANILLA;
        return gap == WorldGenCycle.OverworldGap.LEAD ? Stretch.WWOO : Stretch.BOP;
    }
}
