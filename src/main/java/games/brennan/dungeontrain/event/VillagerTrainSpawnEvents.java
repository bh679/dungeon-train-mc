package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NameComposer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Rerolls trade offers and weighted-randomizes the level (1-5; 2-3 most common,
 * 5 rare) of villagers spawned on the train with a profession. Sticky: a
 * {@code dungeontrain_trades_rerolled} tag is added after the first roll so
 * chunk reloads don't re-roll.
 *
 * <p>Hooks {@link EntityJoinLevelEvent} on the server side, filters to entities
 * carrying the {@link CarriageContentsPlacer#DT_CONTENTS_TAG_PREFIX} tag (set
 * by the carriage contents placer at spawn), and acts only on
 * {@link Villager}s whose profession is set to something with a vanilla trade
 * pool. Jobless ({@link VillagerProfession#NONE}) and nitwit villagers are
 * skipped — they have no trades.</p>
 *
 * <p>Trade regeneration bypasses {@code Villager#updateTrades()} (private in
 * vanilla, no AT in this project) and instead builds a fresh
 * {@link MerchantOffers} directly from {@link VillagerTrades#TRADES}. We loop
 * levels {@code 1..N} and sample up to 2 listings per level, mirroring vanilla
 * {@code Villager#addOffersFromItemListings} behaviour at each level-up.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class VillagerTrainSpawnEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Marker tag: present on a villager once we've rerolled their trades. */
    private static final String REROLLED_TAG = "dungeontrain_trades_rerolled";

    /** Vanilla villager max level (Master). */
    private static final int MAX_LEVEL = 5;

    /** Listings sampled per level when regenerating offers (vanilla default). */
    private static final int OFFERS_PER_LEVEL = 2;

    /**
     * Per-level pick weights, indexed by {@code level - 1}. Sums to 100 so the
     * table reads as percentages: L1 15%, L2 35%, L3 30%, L4 15%, L5 5%.
     * The curve favours Apprentice/Journeyman trades and makes Master-tier
     * (and the strongest enchanted-gear/mending trades it gates) genuinely rare.
     */
    private static final int[] LEVEL_WEIGHTS = {15, 35, 30, 15, 5};
    private static final int TOTAL_WEIGHT = 100;

    private VillagerTrainSpawnEvents() {}

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        Level level = event.getLevel();
        if (level.isClientSide) return;
        if (event.loadedFromDisk()) return;

        Entity entity = event.getEntity();
        if (!(entity instanceof Villager villager)) return;
        if (!isOnTrain(villager)) return;

        // Train villagers are spawned via CarriageContentsPlacer's NBT path
        // (EntityType.create(nbt, level) + level.addFreshEntity), which never
        // calls Mob#finalizeSpawn — so AIN's MobSpawnMixin never fires on
        // them. Bridge that gap here. applyMobName is idempotent (no-ops if
        // CustomName is already set), so chunk-reload re-fires of this event
        // don't re-roll names; covers nitwit/jobless villagers too since
        // we apply before the profession-based early returns below.
        NameComposer.applyMobName(villager, villager.getRandom());

        if (villager.getTags().contains(REROLLED_TAG)) return;

        VillagerData data = villager.getVillagerData();
        VillagerProfession profession = data.getProfession();
        if (profession == VillagerProfession.NONE || profession == VillagerProfession.NITWIT) return;

        Int2ObjectMap<VillagerTrades.ItemListing[]> tradeMap = VillagerTrades.TRADES.get(profession);
        if (tradeMap == null) return;

        int newLevel = pickLevel(villager.getRandom());
        villager.setVillagerData(data.setLevel(newLevel));
        villager.setOffers(generateOffersFor(villager, tradeMap, newLevel));
        villager.addTag(REROLLED_TAG);

        LOGGER.info("[DungeonTrain] Rerolled train villager: uuid={} profession={} level={}",
            villager.getUUID(), profession, newLevel);
    }

    /**
     * Pick a villager level in {@code [1, MAX_LEVEL]} via a weighted cumulative
     * scan over {@link #LEVEL_WEIGHTS}. Package-private for unit tests; the
     * caller is responsible for passing a {@link RandomSource}.
     */
    static int pickLevel(RandomSource rng) {
        int roll = rng.nextInt(TOTAL_WEIGHT);
        int cumulative = 0;
        for (int i = 0; i < LEVEL_WEIGHTS.length; i++) {
            cumulative += LEVEL_WEIGHTS[i];
            if (roll < cumulative) return i + 1;
        }
        return MAX_LEVEL;
    }

    private static boolean isOnTrain(Entity entity) {
        for (String tag : entity.getTags()) {
            if (tag.startsWith(CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX)) return true;
        }
        return false;
    }

    private static MerchantOffers generateOffersFor(Villager villager,
                                                    Int2ObjectMap<VillagerTrades.ItemListing[]> tradeMap,
                                                    int targetLevel) {
        MerchantOffers offers = new MerchantOffers();
        RandomSource rng = villager.getRandom();
        for (int lv = 1; lv <= targetLevel; lv++) {
            VillagerTrades.ItemListing[] listings = tradeMap.get(lv);
            if (listings == null || listings.length == 0) continue;
            List<VillagerTrades.ItemListing> pool = new ArrayList<>(List.of(listings));
            Collections.shuffle(pool, new Random(rng.nextLong()));
            int count = Math.min(OFFERS_PER_LEVEL, pool.size());
            for (int i = 0; i < count; i++) {
                MerchantOffer offer = pool.get(i).getOffer(villager, rng);
                if (offer != null) offers.add(offer);
            }
        }
        return offers;
    }
}
