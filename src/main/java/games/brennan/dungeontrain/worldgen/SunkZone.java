package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBands;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * The stretch of the cycle whose terrain is <b>sunk</b> — generated {@code AmplifiedDrop.drop} blocks
 * lower so the train rides high over it: the {@code ow:sunk} overworld gap that leads into the Amplified
 * band, and the Amplified band's own slot (both fades and core).
 *
 * <p><b>One zone, not two.</b> The Amplified slot's entry fade mixes Amplified chunks with ordinary
 * overworld ones chunk by chunk. If those ordinary chunks stood at stock height the fade would be a field
 * of 80-block steps, with ocean pouring off every one of them into the sunk valleys next door. So every
 * ordinary chunk inside the zone is sunk too (by the sunk-overworld preset, {@code PresetTerrain}), and
 * everything that asks "where is the floor / the lid / twin space" asks this class.</p>
 *
 * <p>Chunk-level questions use the chunk's centre column, so a chunk is never half sunk.</p>
 */
public final class SunkZone {

    private SunkZone() {}

    /** Pure: whether the column at {@code worldX} lies in the sunk zone of {@code cycle}. */
    public static boolean contains(WorldGenCycle cycle, int worldX) {
        return cycle.overworldStyleAt(worldX) == CycleLayout.Style.SUNK
            || LegacyBands.isInSlot(cycle, LegacyBandKind.AMPLIFIED, worldX);
    }

    /** Whether the column at {@code worldX} of {@code level} is sunk — overworld, train worlds only. */
    public static boolean contains(ServerLevel level, int worldX) {
        if (!level.dimension().equals(Level.OVERWORLD)) return false;
        if (!contains(WorldGenCycle.fromConfig(), worldX)) return false;
        return DungeonTrainWorldData.get(level).startsWithTrain();
    }

    /** The column a chunk-level decision reads: the chunk's centre. */
    public static int chunkColumn(int chunkX) {
        return (chunkX << 4) + 8;
    }

    /**
     * Whether chunk {@code (chunkX, chunkZ)} is an <em>ordinary</em> overworld chunk the sunk-overworld
     * preset fills — in the zone, and not claimed by any legacy era's own generator.
     */
    public static boolean isSunkOverworldChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (LegacyBands.kindOfChunk(level, chunkX, chunkZ) != null) return false;
        return contains(level, chunkColumn(chunkX));
    }

    /** Whether chunk {@code (chunkX, chunkZ)}'s terrain is sunk at all — Amplified, or sunk overworld. */
    public static boolean isSunkChunk(ServerLevel level, int chunkX, int chunkZ) {
        LegacyBandKind kind = LegacyBands.kindOfChunk(level, chunkX, chunkZ);
        if (kind == LegacyBandKind.AMPLIFIED) return true;
        return kind == null && contains(level, chunkColumn(chunkX));
    }
}
