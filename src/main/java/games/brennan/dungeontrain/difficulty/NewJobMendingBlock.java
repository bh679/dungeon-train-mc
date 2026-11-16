package games.brennan.dungeontrain.difficulty;

import games.brennan.dungeontrain.train.TrainMembership;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.Collection;

/**
 * Stops players forcing a villager into a Mending trade. Only merchants the train spawned with a job
 * keep vanilla Mending odds (level-ups included). A villager that picks up a job instead (off the
 * train, or a jobless train villager that takes a lectern a player placed) gets its Mending books
 * swapped for another tradeable book, at every level.
 *
 * <p>A train-spawned merchant is one the train rolled at spawn ({@code dungeontrain_trades_rerolled})
 * that isn't marked {@link #BLOCKED_TAG}. The spawn handler adds that marker when it rolls a villager
 * on reload, which is the in-play job case. Vanilla trade generation ({@code Villager#updateTrades},
 * on taking a job and on each level-up) is scrubbed by {@code VillagerNewJobNoMendingMixin}.</p>
 */
public final class NewJobMendingBlock {

    /** Marker on a train villager that took its job in play: it may not sell Mending. */
    public static final String BLOCKED_TAG = "dungeontrain_mending_blocked";

    /** Re-roll attempts for a non-Mending book before the offer is dropped (Mending is ~1 in 40). */
    private static final int MAX_REROLLS = 32;

    private NewJobMendingBlock() {}

    /**
     * True when a villager may sell Mending: it is on the train, the train rolled its trades at spawn,
     * and it hasn't been marked as taking its job in play. Package-private for unit tests.
     */
    static boolean mayOfferMending(boolean onTrain, Collection<String> tags, String rerolledTag) {
        return onTrain && tags != null && tags.contains(rerolledTag) && !tags.contains(BLOCKED_TAG);
    }

    /** True when {@code villager} may sell Mending. */
    public static boolean mayOfferMending(AbstractVillager villager, String rerolledTag) {
        return mayOfferMending(TrainMembership.isOnTrain(villager), villager.getTags(), rerolledTag);
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
