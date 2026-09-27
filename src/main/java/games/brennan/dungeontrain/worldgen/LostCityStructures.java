package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Confines the Big Lost City mod's ruined cities to the {@link LegacyBandKind#LOST_CITY} era's core.
 *
 * <p>The mod ships 42 structure sets of its own that would scatter cities across every overworld biome
 * it likes, and DT adds a much denser set ({@code dungeontrain:lost_city}) so the band reads as a city.
 * Both are global placement grids, so the confinement is a veto on the start: a {@code big_lost_city}
 * structure whose chunk is not safely inside the core is dropped at creation
 * ({@code StructureBasementMixin}), keeping {@code /locate} and chunk references clean. That covers every
 * other band, every other dimension and worlds without a train.</p>
 *
 * <p>"Safely inside" keeps {@link #EDGE_MARGIN_BLOCKS} clear of both core edges: the largest city pieces
 * are ~114 blocks across, so a start closer than that would spill into the crossfade and the
 * neighbouring era's terrain.</p>
 */
public final class LostCityStructures {

    private LostCityStructures() {}

    /** Namespace of every Big Lost City structure. */
    public static final String NAMESPACE = "big_lost_city";

    /** Clearance kept from each core edge — wider than the largest city's footprint. */
    public static final int EDGE_MARGIN_BLOCKS = 128;

    /** Whether {@code id} is a Big Lost City structure. */
    public static boolean isLostCityStructure(ResourceLocation id) {
        return id != null && NAMESPACE.equals(id.getNamespace());
    }

    /** Whether a city may start in chunk {@code chunkX} of {@code level}: overworld, train world, core only. */
    public static boolean allowedAt(ServerLevel level, int chunkX) {
        if (!level.dimension().equals(Level.OVERWORLD)) return false;
        if (!allowedAt(WorldGenCycle.fromConfig(), chunkX)) return false;
        return DungeonTrainWorldData.get(level).startsWithTrain();
    }

    /** Pure form of {@link #allowedAt(ServerLevel, int)}: the chunk plus its margin lies in the era's core. */
    public static boolean allowedAt(WorldGenCycle cycle, int chunkX) {
        if (cycle.legacyLen(LegacyBandKind.LOST_CITY) <= 0L) return false;
        int minX = (chunkX << 4) - EDGE_MARGIN_BLOCKS;
        int maxX = (chunkX << 4) + 15 + EDGE_MARGIN_BLOCKS;
        return cycle.isInLegacyBand(LegacyBandKind.LOST_CITY, minX)
            && cycle.isInLegacyBand(LegacyBandKind.LOST_CITY, maxX);
    }
}
