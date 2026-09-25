package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;

/**
 * Which {@link LapBand} a live world column sits in — {@link LapBand#forSlot} over the configured
 * {@link WorldGenCycle} layout. Worlds without a train, classic (layout-less) cycles and columns before
 * the anchor fall back to the first occurrence of the column's {@link TrainPhase}.
 */
public final class LapBandLocator {

    private LapBandLocator() {}

    /** The band at {@code worldX} of {@code overworld}. */
    public static LapBand at(ServerLevel overworld, int worldX) {
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.hasLayout() && DungeonTrainWorldData.get(overworld).startsWithTrain()) {
            int i = cycle.slotIndexAt(worldX);
            if (i >= 0) return LapBand.forSlot(cycle.layout(), i, cycle.offsetInSlotAt(worldX));
        }
        return LapBand.fallbackOf(TrainPhase.phaseAt(overworld, worldX));
    }

    /**
     * The Nether band of the Overworld↔Nether crossfade at {@code worldX} — the Nether side a tunnel or
     * track tile blends toward. Vanilla or BetterNether by the slot's style.
     */
    public static LapBand netherAt(ServerLevel overworld, int worldX) {
        CycleLayout.Style style = WorldGenCycle.fromConfig().netherStyleAt(worldX);
        return style == CycleLayout.Style.BETTER ? LapBand.M_NETHER : LapBand.V_NETHER;
    }

    /** The overworld side of the Overworld↔Nether crossfade at {@code worldX}. */
    public static LapBand overworldBesideNetherAt(ServerLevel overworld, int worldX) {
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.hasLayout() && DungeonTrainWorldData.get(overworld).startsWithTrain()) {
            int i = cycle.slotIndexAt(worldX);
            if (i >= 0) return LapBand.overworldBesideNether(cycle.layout(), i, cycle.offsetInSlotAt(worldX));
        }
        return LapBand.V_OVERWORLD_1;
    }
}
