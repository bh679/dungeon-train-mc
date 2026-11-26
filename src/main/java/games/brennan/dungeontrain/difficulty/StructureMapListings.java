package games.brennan.dungeontrain.difficulty;

import net.minecraft.world.entity.npc.VillagerTrades;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps explorer-map trades from freezing the server.
 *
 * <p>Vanilla's {@link VillagerTrades.TreasureMapForEmeralds#getOffer} runs
 * {@code ServerLevel.findNearestMapStructure(tag, pos, 100, true)} on the server thread to find the map's target.
 * In DT's overworld each chunk of that 100-chunk search walks DT's biome source, so a cartographer rolling the
 * trade froze the server for minutes (v0.2031.0).</p>
 *
 * <p>Async Locator Refined ({@value #ASYNC_LOCATOR_MOD_ID}, a required dependency) moves that search to a background
 * thread: the trade shows a pending map that fills in when the search ends. Without it, both roll paths drop the
 * listing before picking: {@code VillagerTrainSpawnEvents#generateOffersFor} (train villagers, at spawn) and
 * {@code MerchantNoStructureSearchMixin} (every villager's level-up / new job, wandering traders).</p>
 */
public final class StructureMapListings {

    /** Async Locator Refined's mod id. */
    public static final String ASYNC_LOCATOR_MOD_ID = "asynclocator";

    private static Boolean asyncLocator;

    private StructureMapListings() {}

    /** True when explorer-map listings may roll: Async Locator is loaded, so their search runs off the server thread. */
    public static boolean mapsAllowed() {
        Boolean loaded = asyncLocator;
        if (loaded == null) {
            ModList mods = ModList.get();
            loaded = mods != null && mods.isLoaded(ASYNC_LOCATOR_MOD_ID);
            asyncLocator = loaded;
        }
        return loaded;
    }

    /** {@code listings} as they may roll: unchanged when {@link #mapsAllowed()}, else without the map listings. */
    public static VillagerTrades.ItemListing[] rollable(VillagerTrades.ItemListing[] listings) {
        return rollable(listings, mapsAllowed());
    }

    /** A new list of {@code listings} as they may roll (see {@link #rollable(VillagerTrades.ItemListing[])}). */
    public static List<VillagerTrades.ItemListing> rollable(List<VillagerTrades.ItemListing> listings) {
        return rollable(listings, mapsAllowed());
    }

    /** {@link #rollable(VillagerTrades.ItemListing[])} with the mod check given. Package-private for unit tests. */
    static VillagerTrades.ItemListing[] rollable(VillagerTrades.ItemListing[] listings, boolean mapsAllowed) {
        return mapsAllowed ? listings : withoutStructureSearch(listings);
    }

    /** {@link #rollable(List)} with the mod check given. Package-private for unit tests. */
    static List<VillagerTrades.ItemListing> rollable(List<VillagerTrades.ItemListing> listings, boolean mapsAllowed) {
        return mapsAllowed ? new ArrayList<>(listings) : withoutStructureSearch(listings);
    }

    /** True when rolling {@code listing} would search the world for a structure. */
    public static boolean isStructureSearch(VillagerTrades.ItemListing listing) {
        return listing instanceof VillagerTrades.TreasureMapForEmeralds;
    }

    /** {@code listings} without the structure-searching ones, in order; the same array when none match. */
    public static VillagerTrades.ItemListing[] withoutStructureSearch(VillagerTrades.ItemListing[] listings) {
        if (listings == null) return null;
        boolean any = false;
        for (VillagerTrades.ItemListing listing : listings) {
            if (isStructureSearch(listing)) {
                any = true;
                break;
            }
        }
        if (!any) return listings;
        return withoutStructureSearch(List.of(listings)).toArray(new VillagerTrades.ItemListing[0]);
    }

    /** A new list of {@code listings} without the structure-searching ones, in order. */
    public static List<VillagerTrades.ItemListing> withoutStructureSearch(List<VillagerTrades.ItemListing> listings) {
        List<VillagerTrades.ItemListing> kept = new ArrayList<>(listings.size());
        for (VillagerTrades.ItemListing listing : listings) {
            if (!isStructureSearch(listing)) kept.add(listing);
        }
        return kept;
    }
}
