package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBands;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Confines the Big Lost City mod's ruined cities to the {@link LegacyBandKind#LOST_CITY} era.
 *
 * <p>The mod ships 42 structure sets of its own that would scatter cities across every overworld biome
 * it likes, and DT adds a much denser set ({@code dungeontrain:lost_city}) so the band reads as a city.
 * Both are global placement grids, so the confinement is a veto on the start: a {@code big_lost_city}
 * structure whose chunk may not host one is dropped at creation ({@code StructureBasementMixin}), keeping
 * {@code /locate} and chunk references clean. That covers every other band, every other dimension and
 * worlds without a train.</p>
 *
 * <p>A city may start in any chunk the era owns — including the chunks the entry crossfade out of
 * Amplified rolls to Lost City, so the city thickens in across the run-in as that roll's share climbs.
 * The way out is different: Beta's old generator drops every structure piece in its chunks, so a city
 * reaching into the exit crossfade would be cut in half. A start there must keep
 * {@link #EXIT_MARGIN_BLOCKS} (wider than the largest city) short of the core's end.</p>
 *
 * <p>The mod lets its big buildings (skyscrapers, power plant, houses, store, warehouse, ferris wheel)
 * start almost only in plains, and the ground beside the track is as often taiga or forest — so the ride
 * would pass cars and tents but few buildings. DT ships trackside copies of them
 * ({@code dungeontrain:lost_city/<name>}: the same structure, any land biome) that may start only within
 * {@link #TRACKSIDE_BLOCKS} of the track; away from it the mod's own biome rules stand.</p>
 */
public final class LostCityStructures {

    private LostCityStructures() {}

    /** Namespace of every Big Lost City structure. */
    public static final String NAMESPACE = "big_lost_city";

    /** DT's trackside copies of the big buildings: {@code dungeontrain:lost_city/<name>}. */
    public static final String TRACKSIDE_PREFIX = "lost_city/";

    /** How far from the track centre a trackside copy may start — the flattened strip's outer edge. */
    public static final int TRACKSIDE_BLOCKS = 160;

    /** Clearance kept before the core's end — wider than the largest city's ~114-block footprint. */
    public static final int EXIT_MARGIN_BLOCKS = 128;

    /** Whether {@code id} is a Big Lost City structure, or one of DT's trackside copies of one. */
    public static boolean isLostCityStructure(ResourceLocation id) {
        return id != null && (NAMESPACE.equals(id.getNamespace()) || isTracksideCopy(id));
    }

    /** Whether {@code id} is one of DT's trackside copies of a big building. */
    public static boolean isTracksideCopy(ResourceLocation id) {
        return id != null && "dungeontrain".equals(id.getNamespace()) && id.getPath().startsWith(TRACKSIDE_PREFIX);
    }

    /**
     * Whether structure {@code id} may start in chunk {@code (chunkX, chunkZ)} of {@code level}: overworld,
     * train world, era chunk, and — for a trackside copy — beside the track.
     */
    public static boolean allowedAt(ServerLevel level, int chunkX, int chunkZ, ResourceLocation id) {
        if (!level.dimension().equals(Level.OVERWORLD)) return false;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        if (!data.startsWithTrain()) return false;
        int trackZ = TrackGeometry.from(data.dims(), data.getTrainY()).trackCenterZ();
        return allowedAt(data.getGenerationSeed(), WorldGenCycle.fromConfig(), chunkX, chunkZ, id, trackZ);
    }

    /** Pure form of {@link #allowedAt(ServerLevel, int, int, ResourceLocation)}. */
    public static boolean allowedAt(long seed, WorldGenCycle cycle, int chunkX, int chunkZ,
                                    ResourceLocation id, int trackCenterZ) {
        if (isTracksideCopy(id) && !isTrackside(chunkZ, trackCenterZ)) return false;
        return allowedAt(seed, cycle, chunkX, chunkZ);
    }

    /** Whether any column of chunk row {@code chunkZ} lies within {@link #TRACKSIDE_BLOCKS} of the track centre. */
    public static boolean isTrackside(int chunkZ, int trackCenterZ) {
        int minZ = chunkZ << 4;
        int maxZ = minZ + 15;
        int nearest = trackCenterZ < minZ ? minZ : Math.min(trackCenterZ, maxZ);
        return Math.abs(nearest - trackCenterZ) <= TRACKSIDE_BLOCKS;
    }

    /**
     * The era rule alone, for any Lost City structure: the chunk rolled Lost City (core, or its share
     * of a crossfade) and the chunk plus {@link #EXIT_MARGIN_BLOCKS} ends before the core does.
     */
    public static boolean allowedAt(long seed, WorldGenCycle cycle, int chunkX, int chunkZ) {
        if (cycle == null || cycle.legacyLen(LegacyBandKind.LOST_CITY) <= 0L) return false;
        if (LegacyBands.kindOfChunk(seed, cycle, chunkX, chunkZ) != LegacyBandKind.LOST_CITY) return false;
        double reach = cycle.legacyCoreProgress(LegacyBandKind.LOST_CITY, (chunkX << 4) + 15 + EXIT_MARGIN_BLOCKS);
        return !Double.isNaN(reach) && reach < 1.0D;
    }
}
