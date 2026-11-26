package games.brennan.dungeontrain.difficulty;

import net.minecraft.world.entity.npc.VillagerTrades;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps explorer-map trades out of every trade roll.
 *
 * <p>Vanilla's {@link VillagerTrades.TreasureMapForEmeralds#getOffer} runs
 * {@code ServerLevel.findNearestMapStructure(tag, pos, 100, true)} on the server thread to find the map's target.
 * In DT's overworld each chunk of that 100-chunk search walks DT's biome source, so a cartographer rolling the
 * trade froze the server for minutes (v0.2031.0). Both roll paths drop the listing before picking:
 * {@code VillagerTrainSpawnEvents#generateOffersFor} (train villagers, at spawn) and
 * {@code MerchantNoStructureSearchMixin} (every villager's level-up / new job, wandering traders).</p>
 */
public final class StructureMapListings {

    private StructureMapListings() {}

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
