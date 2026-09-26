package games.brennan.dungeontrain.worldgen;

/**
 * Which overworld stretches take their look from a third-party worldgen mod. With the shipped
 * {@code worldgenCycleOrder} each theme lap's overworld stretches wear its {@link LapTheme}: both
 * William Wythers' Overhauled Overworld (WWOO + BetterNether + BetterEnd lap) or both Biomes O' Plenty
 * (BoP lap), chosen per world ({@link LapThemes}). An order with explicit {@code ow:wwoo} / {@code ow:bop}
 * slots keeps those. The classic (blank-order) cycle keeps its old rule: on every <b>odd</b> lap the
 * gap <b>before</b> the Nether band is WWOO and the gap <b>after</b> it is BoP.
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

    /** The stretch at this world-X; {@link Stretch#VANILLA} when the cycle is missing. */
    public static Stretch at(WorldGenCycle cycle, int worldX) {
        if (cycle == null) return Stretch.VANILLA;
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

    /**
     * The look this world-X <b>wears</b>: its own stretch ({@link #at}), or — in a band transition that
     * borders a modded stretch — that stretch's look carried on through the transition
     * ({@link WorldGenCycle#bleedingOverworldStyleAt}). Drives decoration, biome choice and colours;
     * {@link #at} stays the stretch itself (teleports, debug listings).
     */
    public static Stretch lookAt(WorldGenCycle cycle, int worldX) {
        Stretch own = at(cycle, worldX);
        if (own != Stretch.VANILLA || cycle == null) return own;
        CycleLayout.Style bleed = cycle.bleedingOverworldStyleAt(worldX);
        if (bleed == CycleLayout.Style.WWOO) return Stretch.WWOO;
        if (bleed == CycleLayout.Style.BOP) return Stretch.BOP;
        return Stretch.VANILLA;
    }
}
