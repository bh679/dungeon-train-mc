package games.brennan.dungeontrain.difficulty;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.compat.GearResaleFloor;
import games.brennan.dungeontrain.train.TrainMembership;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * One full roll of a villager's trades, as Dungeon Train does it: listings level by level (up to
 * {@value #OFFERS_PER_LEVEL} per level, like vanilla's level-ups), each sold item scaled to the
 * villager's carriage ({@link TradeGearScaler}), Mending books optionally replaced
 * ({@link NewJobMendingBlock}), then the Trade Everything resale floor ({@link GearResaleFloor}).
 * Used by the train-villager spawn roll and by the Reroll Trades perk.
 *
 * <p>Trade generation bypasses {@code Villager#updateTrades()} (private in vanilla, no AT in this
 * project) and builds offers straight from {@link VillagerTrades#TRADES}.</p>
 */
public final class VillagerOfferRoll {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Listings sampled per level (vanilla default). */
    static final int OFFERS_PER_LEVEL = 2;

    private VillagerOfferRoll() {}

    /**
     * Rolls a fresh set of offers for {@code villager} at {@code targetLevel}, scaled to its own
     * carriage. Empty when its profession has no trades. Draws from the villager's own random.
     */
    public static Optional<MerchantOffers> roll(Villager villager, ServerLevel level, int targetLevel, boolean blockMending) {
        Int2ObjectMap<VillagerTrades.ItemListing[]> tradeMap =
            VillagerTrades.TRADES.get(villager.getVillagerData().getProfession());
        if (tradeMap == null) return Optional.empty();

        int carriageIndex = TrainMembership.carriageIndexOf(villager).orElse(0);
        int posTier = DifficultyProgression.positionTier(carriageIndex);

        MerchantOffers offers = generate(villager, tradeMap, targetLevel);
        MerchantOffers scaled = new MerchantOffers();
        for (MerchantOffer offer : offers) {
            scaled.add(sellsMap(offer) ? offer : TradeGearScaler.scale(offer, carriageIndex, posTier,
                villager.getRandom(), level.registryAccess()));
        }
        if (blockMending) NewJobMendingBlock.replaceMendingBooks(villager, scaled, 0);
        return Optional.of(withResaleFloor(scaled));
    }

    private static MerchantOffers generate(Villager villager,
                                           Int2ObjectMap<VillagerTrades.ItemListing[]> tradeMap,
                                           int targetLevel) {
        MerchantOffers offers = new MerchantOffers();
        RandomSource rng = villager.getRandom();
        for (int lv = 1; lv <= targetLevel; lv++) {
            VillagerTrades.ItemListing[] listings = tradeMap.get(lv);
            if (listings == null || listings.length == 0) continue;
            // Explorer maps search for a structure: they roll only when Async Locator runs it off-thread (StructureMapListings).
            List<VillagerTrades.ItemListing> pool = StructureMapListings.rollable(List.of(listings));
            if (pool.isEmpty()) continue;
            Collections.shuffle(pool, new Random(rng.nextLong()));
            int count = Math.min(OFFERS_PER_LEVEL, pool.size());
            for (int i = 0; i < count; i++) {
                MerchantOffer offer = pool.get(i).getOffer(villager, rng);
                if (offer != null) offers.add(offer);
            }
        }
        return offers;
    }

    /**
     * Raises every gear and enchanted-book offer to at least what Trade Everything would pay for it
     * ({@link GearResaleFloor}), so buying here and selling to another villager can't profit. Without
     * Trade Everything, or if its API throws, the offers are returned unchanged.
     */
    private static MerchantOffers withResaleFloor(MerchantOffers offers) {
        if (!ModList.get().isLoaded("tradeeverything")) return offers;
        try {
            MerchantOffers floored = new MerchantOffers();
            for (MerchantOffer offer : offers) floored.add(sellsMap(offer) ? offer : GearResaleFloor.apply(offer));
            return floored;
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not apply the gear resale floor; offers left as rolled: {}", t.toString());
            return offers;
        }
    }

    /**
     * True for an explorer-map offer. Kept as rolled, never copied: Async Locator fills a pending map in place, by
     * the stack it handed out, so a copied offer would stay "locating" for good. Maps take no gear scaling anyway.
     */
    private static boolean sellsMap(MerchantOffer offer) {
        return offer.getResult().is(Items.FILLED_MAP);
    }
}
