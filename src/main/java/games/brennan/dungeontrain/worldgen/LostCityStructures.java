package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBands;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

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
 * start almost only in plains. DT ships copies of them ({@code dungeontrain:lost_city/<name>}: the same
 * structure, every overworld biome — oceans and rivers included, where Lost City Terrain Fit's seating sets them
 * on the seabed) that may start in any chunk the era owns, so the ride passes buildings whatever the ground is.
 * The sibling ships all-biome copies of its own ({@link #isTerrainFitCopy}); DT never starts those.</p>
 *
 * <p>The city fades in: nothing on the Nether's exit range, then from the foot of its fall a start is kept
 * with a probability that climbs from {@link #FADE_FLOOR} to full {@link #FADE_BLOCKS} further on
 * ({@link #density}); Lap 1's WWOO stretch keeps a few percent of its starts as a foretaste — built from
 * only a per-world pick of the buildings ({@link #wwooBuildings}), so each world's foretaste differs and the
 * stretch loads fewer of the mod's large templates.</p>
 *
 * <p>How a city sits in the ground — its template's natural pad and lower air yielding to the stretch's own
 * terrain — belongs to the Lost City Terrain Fit sibling mod (jarJar'd; {@code bh679/lostcityterrainfit-mc}).</p>
 */
public final class LostCityStructures {

    private LostCityStructures() {}

    /** Namespace of every Big Lost City structure. */
    public static final String NAMESPACE = "big_lost_city";

    /** DT's copies of the big buildings: {@code dungeontrain:lost_city/<name>}. */
    public static final String TRACKSIDE_PREFIX = "lost_city/";

    /**
     * Clearance kept before the core's end — wider than the largest placed piece: house2lt's 80-block footprint
     * (36×50×80) is the widest, ruined_skyscraperlt (55×147×47) and tall_skyscraperlt (39×159×38) the tallest.
     */
    public static final int EXIT_MARGIN_BLOCKS = 160;

    /** Whether {@code id} is a Big Lost City structure, or one of DT's copies of one. */
    public static boolean isLostCityStructure(ResourceLocation id) {
        return id != null && (NAMESPACE.equals(id.getNamespace()) || isTracksideCopy(id));
    }

    /** Lost City Terrain Fit's all-biome copies: {@code lostcityterrainfit:all_biome/<name>}. */
    public static boolean isTerrainFitCopy(ResourceLocation id) {
        return id != null && "lostcityterrainfit".equals(id.getNamespace()) && id.getPath().startsWith("all_biome/");
    }

    /** Whether {@code id} is one of DT's copies of a big building. */
    public static boolean isTracksideCopy(ResourceLocation id) {
        return id != null && "dungeontrain".equals(id.getNamespace()) && id.getPath().startsWith(TRACKSIDE_PREFIX);
    }

    /**
     * Whether structure {@code id} may start in chunk {@code (chunkX, chunkZ)} of {@code level}: overworld,
     * train world, era chunk. The mod's own and DT's copies follow the same rule.
     */
    public static boolean allowedAt(ServerLevel level, int chunkX, int chunkZ, ResourceLocation id) {
        if (!level.dimension().equals(Level.OVERWORLD)) return false;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        if (!data.startsWithTrain()) return false;
        long seed = data.getGenerationSeed();
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        // the chunk part once per chunk — vanilla retries the whole set in a refused one (LostCityChunkVeto)
        return switch (LostCityChunkVeto.verdict(seed, cycle, chunkX, chunkZ)) {
            case DENY -> false;
            case ALLOW -> true;
            case ALLOW_IF_WWOO_BUILDING -> LostCityWwooCensus.buildings(level, seed, cycle).contains(building(id));
        };
    }

    /**
     * {@link #allowedAt(long, WorldGenCycle, int, int)} for structure {@code id}: in the WWOO stretch only the
     * world's {@code wwooBuildings} may start; everywhere else the chunk rule alone decides.
     */
    public static boolean allowedAt(long seed, WorldGenCycle cycle, int chunkX, int chunkZ, ResourceLocation id,
                                    Set<String> wwooBuildings) {
        if (!allowedAt(seed, cycle, chunkX, chunkZ)) return false;
        return !inWwooStretch(cycle, chunkX) || wwooBuildings.contains(building(id));
    }

    /** Whether {@code chunkX} lies in a WWOO overworld stretch (the foretaste, not the Lost City run). */
    public static boolean inWwooStretch(WorldGenCycle cycle, int chunkX) {
        return cycle != null && cycle.overworldStyleAt(chunkX << 4) == CycleLayout.Style.WWOO;
    }

    /**
     * The building {@code id} places: its path without DT's {@link #TRACKSIDE_PREFIX}, so a trackside copy
     * and the mod's original ({@code dungeontrain:lost_city/warehouse}, {@code big_lost_city:warehouse}) are
     * one building.
     */
    public static String building(ResourceLocation id) {
        String path = id.getPath();
        return isTracksideCopy(id) ? path.substring(TRACKSIDE_PREFIX.length()) : path;
    }

    /**
     * The buildings allowed in this world's WWOO stretch: the distinct {@link #building}s of {@code ids},
     * shuffled by {@code seed}, keeping {@code min(half of them, expectedStarts)} — no more kinds than the
     * stretch has cities to show them. Pure and stable per seed, so every session of a world generates the
     * same foretaste while each world gets its own.
     */
    public static Set<String> wwooBuildings(long seed, Collection<ResourceLocation> ids, int expectedStarts) {
        Set<String> all = new LinkedHashSet<>();
        for (ResourceLocation id : ids) all.add(building(id));
        int keep = Math.max(0, Math.min(all.size() / 2, expectedStarts));
        Set<String> kept = new LinkedHashSet<>();
        all.stream()
                .sorted(Comparator.comparingDouble((String b) -> hash01(seed ^ WWOO_PICK_SALT, b.hashCode(), 0))
                        .thenComparing(Comparator.naturalOrder()))
                .limit(keep)
                .forEach(kept::add);
        return Set.copyOf(kept);
    }

    /** Separates {@link #wwooBuildings}'s shuffle from the chunk roll, which shares {@link #hash01}. */
    private static final long WWOO_PICK_SALT = 0x5741_574F_4F4CL;

    /**
     * The era rule with the density roll, for any Lost City structure. In the Lost City run the chunk must
     * have rolled Lost City (core, or its share of a crossfade) and end, with {@link #EXIT_MARGIN_BLOCKS},
     * before the core does; it is then kept with probability {@link #density}, so the city fades in. In Lap
     * 1's WWOO stretch a chunk is kept with {@link #WWOO_STRETCH_DENSITY} — a few ruins as a foretaste.
     */
    public static boolean allowedAt(long seed, WorldGenCycle cycle, int chunkX, int chunkZ) {
        double density = density(cycle, chunkX);
        if (density <= 0.0D) return false;
        boolean wwoo = cycle.overworldStyleAt(chunkX << 4) == CycleLayout.Style.WWOO;
        if (!wwoo && LegacyBands.kindOfChunk(seed, cycle, chunkX, chunkZ) != LegacyBandKind.LOST_CITY) return false;
        return hash01(seed, chunkX, chunkZ) < density;
    }

    /** How far past the foot of the Nether's fall the city reaches full density, in base blocks. */
    public static final int FADE_BLOCKS = 2900;

    /**
     * The density at the foot of the fall: a sprinkling of buildings right below the range that thickens into the
     * city, rather than half a city appearing at once (0.5 until 0.1022.x).
     */
    public static final double FADE_FLOOR = 0.15;

    /** Share of the placement grid's starts kept in the WWOO overworld stretch: roughly 2–6 Lost City structures near the track. */
    public static final double WWOO_STRETCH_DENSITY = 0.024;

    /**
     * The share of placement-grid starts kept for a chunk column at {@code chunkX}: 0 outside the Lost City
     * run and the WWOO stretch, and 0 on the Nether's exit range itself; from the foot of its fall
     * ({@link #fallFoot}) {@link #FADE_FLOOR}, rising linearly to 1 at {@link #FADE_BLOCKS} past the foot
     * and staying there; in the WWOO stretch, {@link #WWOO_STRETCH_DENSITY}.
     */
    public static double density(WorldGenCycle cycle, int chunkX) {
        if (cycle == null) return 0.0D;
        int worldX = chunkX << 4;
        if (cycle.overworldStyleAt(worldX) == CycleLayout.Style.WWOO) return WWOO_STRETCH_DENSITY;
        if (!inEra(cycle, chunkX)) return 0.0D;
        long into = blocksIntoRun(cycle, worldX);
        if (into < 0L) return 1.0D;
        long past = into - fallFoot(cycle);
        if (past < 0L) return 0.0D;
        double t = past >= FADE_BLOCKS ? 1.0D : (double) past / FADE_BLOCKS;
        return FADE_FLOOR + (1.0D - FADE_FLOOR) * t;
    }

    /**
     * Base blocks from the lead-in's start to the foot of the Nether's fall: the mega-mountain plateau and
     * the two tallest mountain stages, after which the descent eases (stage multipliers 4, 2, 1 and the
     * beach). Buildings begin below that.
     */
    public static long fallFoot(WorldGenCycle cycle) {
        return Math.max(0, cycle.megaHold()) + 2L * Math.max(0, cycle.stageBlocks());
    }

    /**
     * Base blocks from the start of the Lost City run's lead-in — the last {@code legacyLeadIn} blocks of the
     * Nether slot before it — to {@code worldX}; {@code -1} without a layout or outside both slots.
     */
    static long blocksIntoRun(WorldGenCycle cycle, int worldX) {
        CycleLayout layout = cycle.layout();
        if (layout == null) return -1L;
        int legacy = layout.legacySlotOf(LegacyBandKind.LOST_CITY);
        int slot = cycle.slotIndexAt(worldX);
        if (legacy < 0 || slot < 0) return -1L;
        long lead = layout.legacyLeadIn(legacy);
        long local = cycle.slotLocal(worldX);
        if (slot == legacy) return lead + local;
        if (slot == legacy - 1) return lead - (layout.length(slot) - local);   // the lead-in, in the Nether's tail
        return -1L;
    }

    /** The era rule alone: the chunk rolled Lost City and ends before the core's exit margin. */
    static boolean inEra(WorldGenCycle cycle, int chunkX) {
        if (cycle.legacyLen(LegacyBandKind.LOST_CITY) <= 0L) return false;
        double reach = cycle.legacyCoreProgress(LegacyBandKind.LOST_CITY, (chunkX << 4) + 15 + EXIT_MARGIN_BLOCKS);
        if (Double.isNaN(reach)) {
            // the lead-in on the Nether's exit mountains, before the Lost City's own slot
            return cycle.isInLegacyLeadIn(LegacyBandKind.LOST_CITY, chunkX << 4);
        }
        return reach < 1.0D;
    }

    // splitmix64-style finaliser, uniform in [0,1) per (seed, chunkX, chunkZ); same idiom as LegacyBands.
    private static final int OWN_SALT = 61;

    static double hash01(long seed, int a, int b) {
        long h = seed * 0x9E3779B97F4A7C15L + OWN_SALT * 0xD1B54A32D192ED03L;
        h ^= (long) a * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h ^= (long) b * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return (h >>> 11) * 0x1.0p-53;
    }
}
