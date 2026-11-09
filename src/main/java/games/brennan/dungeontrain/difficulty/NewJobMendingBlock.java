package games.brennan.dungeontrain.difficulty;

import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.Collection;

/**
 * Only librarians the train spawns may sell Mending. Every other villager that picks up a job (off
 * the train, or a jobless train villager that takes a lectern a player placed) gets its Mending
 * books swapped for another tradeable book, at every level.
 *
 * <p>The train's spawn roll ({@code VillagerTrainSpawnEvents}) marks its merchants with
 * {@link #MENDING_OK_TAG}. Vanilla trade generation ({@code Villager#updateTrades}, on taking a job
 * and on each level-up) is scrubbed by {@code VillagerNewJobNoMendingMixin} unless the villager
 * carries it; a DT roll on reload (the in-play job case) is scrubbed by the spawn handler.</p>
 */
public final class NewJobMendingBlock {

    /** Marker on a merchant whose trades the train rolled at spawn: it may sell Mending. */
    public static final String MENDING_OK_TAG = "dungeontrain_mending_ok";

    /** Re-roll attempts for a non-Mending book before the offer is dropped (Mending is ~1 in 40). */
    private static final int MAX_REROLLS = 32;

    private NewJobMendingBlock() {}

    /** True when a villager with these tags may sell Mending. Package-private for unit tests. */
    static boolean mayOfferMending(Collection<String> tags) {
        return tags != null && tags.contains(MENDING_OK_TAG);
    }

    /** True when {@code villager} may sell Mending. */
    public static boolean mayOfferMending(AbstractVillager villager) {
        return mayOfferMending(villager.getTags());
    }

    /**
     * Swaps every Mending book in {@code offers} from index {@code from} on for a fresh vanilla
     * librarian book ({@code #minecraft:tradeable}, same XP) that isn't Mending. In place.
     */
    public static void replaceMendingBooks(AbstractVillager villager, MerchantOffers offers, int from) {
        for (int i = Math.max(0, from); i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            if (!offer.getResult().is(Items.ENCHANTED_BOOK)
                || !MendingTradeStock.sellsMending(offer.getResult())) continue;
            MerchantOffer replacement = rerollBook(villager, offer.getXp());
            if (replacement != null) {
                offers.set(i, replacement);
            } else {
                offers.remove(i--);
            }
        }
    }

    private static MerchantOffer rerollBook(AbstractVillager villager, int xp) {
        VillagerTrades.ItemListing listing = new VillagerTrades.EnchantBookForEmeralds(xp, EnchantmentTags.TRADEABLE);
        for (int attempt = 0; attempt < MAX_REROLLS; attempt++) {
            MerchantOffer offer = listing.getOffer(villager, villager.getRandom());
            if (offer != null && !MendingTradeStock.sellsMending(offer.getResult())) return offer;
        }
        return null;
    }
}
