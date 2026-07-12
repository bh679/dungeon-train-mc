package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NameComposer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.difficulty.ProceduralTiers;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.TrainMembership;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Rerolls trade offers and weighted-randomizes the level (1-5; 1-2 most common,
 * 5 rare) of villagers spawned on the train with a profession. When difficulty
 * progression is enabled the rolled level is capped to the current mob weapon
 * stage (none → 1, wood → 2, stone → 3, iron → 4, diamond → 5) via
 * {@link #maxLevelForTier}, so early-run villagers stay low-level and the full
 * range unlocks as mob weapons advance. Sticky: established merchants carry a
 * {@code dungeontrain_trades_rerolled} tag so reloads don't re-roll their
 * offers — the one exception is a jobless villager that reloads next to a job
 * block, which is repaired (claims the job + gets a fresh trade set).
 *
 * <p>Hooks {@link EntityJoinLevelEvent} on the server side and filters to
 * entities carrying the {@link CarriageContentsPlacer#DT_CONTENTS_TAG_PREFIX}
 * tag (set by the carriage contents placer at spawn). A <strong>jobless</strong>
 * ({@link VillagerProfession#NONE}) villager first claims the nearest vanilla
 * job-site block within 5 blocks via {@link VillagerJobSiteAssigner} — vanilla's
 * own POI acquisition can't do this on a Sable carriage — then is treated like
 * any other professioned villager. Nitwits, and jobless villagers with no job
 * block nearby, are left as-is.</p>
 *
 * <p>Every villager that ends up with a real profession is also made
 * <strong>persistent</strong> against vanilla's {@code ResetProfession} brain
 * rule (which would otherwise strip a level-1, never-traded villager that has no
 * bound job-site POI — i.e. all of them, on a ship) by granting 1 trade-XP. See
 * the inline note in {@link #onEntityJoin}.</p>
 *
 * <p>Trade regeneration bypasses {@code Villager#updateTrades()} (private in
 * vanilla, no AT in this project) and instead builds a fresh
 * {@link MerchantOffers} directly from {@link VillagerTrades#TRADES}. We loop
 * levels {@code 1..N} and sample up to 2 listings per level, mirroring vanilla
 * {@code Villager#addOffersFromItemListings} behaviour at each level-up.</p>
 */
public final class VillagerTrainSpawnEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Marker tag: present on a villager once we've rerolled their trades. Public
     * so {@link games.brennan.dungeontrain.train.CarriageContentsPlacer} can strip
     * it from baked template NBT at spawn time — an editor capture freezes this tag
     * into the template, which would otherwise make this handler skip the reroll
     * (line {@code onEntityJoin} gate) and pin every spawn to identical trades.
     */
    public static final String REROLLED_TAG = "dungeontrain_trades_rerolled";

    /** Vanilla villager max level (Master). */
    private static final int MAX_LEVEL = 5;

    /** Listings sampled per level when regenerating offers (vanilla default). */
    private static final int OFFERS_PER_LEVEL = 2;

    /**
     * Per-level pick weights, indexed by {@code level - 1}. Sums to 100 so the
     * table reads as percentages: L1 35%, L2 39%, L3 23%, L4 2%, L5 1%.
     * The curve makes Novice/Apprentice/Journeyman the overwhelming majority
     * (97% combined) and pushes both Expert and Master tiers — which gate the
     * strongest enchanted-gear / mending trades — into a vanishingly rare
     * 3%-combined tail.
     */
    private static final int[] LEVEL_WEIGHTS = {35, 39, 23, 2, 1};
    private static final int TOTAL_WEIGHT = 100;

    private VillagerTrainSpawnEvents() {}

        public static void onEntityJoin(net.minecraft.world.entity.Entity joiningEntity, net.minecraft.world.level.Level joinLevel, boolean loadedFromDisk) {
        Level level = joinLevel;
        if (!(level instanceof ServerLevel serverLevel)) return;

        Entity entity = joiningEntity;
        if (!(entity instanceof Villager villager)) return;
        if (!TrainMembership.isOnTrain(villager)) return;

        // Train villagers are spawned via CarriageContentsPlacer's NBT path
        // (EntityType.create(nbt, level) + level.addFreshEntity), which never
        // calls Mob#finalizeSpawn — so AIN's MobSpawnMixin never fires on them.
        // Bridge that gap here. applyMobName is idempotent (no-ops if CustomName
        // is already set), so reload re-fires of this event don't re-roll names.
        NameComposer.applyMobName(villager, villager.getRandom());

        VillagerData data = villager.getVillagerData();
        VillagerProfession profession = data.getProfession();

        // (1) Claim the nearest job block. A jobless (NONE) train villager within
        // 5 blocks of a vanilla job-site block adopts that block's profession —
        // vanilla's own POI acquisition can't do this on a Sable carriage (the
        // blocks live in the ship's plot, not where AcquirePoi searches). This
        // runs on BOTH fresh spawn AND reload — deliberately NOT gated by
        // loadedFromDisk / REROLLED_TAG — so villagers that vanilla stripped to
        // jobless before this fix existed, and were saved that way, get repaired
        // the moment they reload next to a block. Self-limiting: once a villager
        // has a profession this NONE-gated block no longer matches. Nitwits are
        // never reassigned.
        boolean justClaimed = false;
        if (profession == VillagerProfession.NONE) {
            VillagerProfession claimed =
                VillagerJobSiteAssigner.assignNearestJobSite(villager, serverLevel);
            if (claimed != VillagerProfession.NONE) {
                data = data.setProfession(claimed);
                villager.setVillagerData(data);
                profession = claimed;
                justClaimed = true;
                LOGGER.info("[DungeonTrain] Train villager {} claimed nearest job block -> {}",
                    villager.getUUID(), claimed);
            }
        }

        // Jobless-with-no-block and nitwit villagers have no trades — leave them.
        if (profession == VillagerProfession.NONE || profession == VillagerProfession.NITWIT) return;

        // (2) Persist the profession against vanilla's ResetProfession brain rule
        // (an always-on CORE behaviour): it strips a villager whose JOB_SITE POI
        // is absent — always true on a ship — while villagerXp == 0 AND
        // level <= 1, which is exactly the "armorer next to a blast furnace with
        // no job" symptom. Granting 1 trade-XP makes the xp == 0 clause false, so
        // the rule skips it and the profession (authored OR block-assigned above)
        // sticks for good. Applied on every join — fresh, reloaded, or just
        // claimed — but only writes when xp is still 0, so it's idempotent.
        if (villager.getVillagerXp() == 0) {
            villager.setVillagerXp(1);
        }

        // (3) Trade reroll. Generate offers only the first time we process a
        // villager as a merchant: a fresh spawn we haven't rerolled, or one that
        // just claimed a job this tick. An established villager loaded from disk
        // keeps its saved offers — we must not re-roll its trades on every chunk
        // load. (Restocking never happens regardless — our offers are pre-baked,
        // not workstation-driven.)
        if (villager.getTags().contains(REROLLED_TAG) && !justClaimed) return;

        Int2ObjectMap<VillagerTrades.ItemListing[]> tradeMap = VillagerTrades.TRADES.get(profession);
        if (tradeMap == null) return;

        int newLevel;
        if (DungeonTrainConfig.getDifficultyEnabled()) {
            newLevel = cappedLevel(villager.getRandom(), DifficultyProgression.currentTier(serverLevel));
        } else {
            newLevel = pickLevel(villager.getRandom());
        }
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

    /**
     * Maximum villager level allowed at {@code difficultyTier}, paired to the mob
     * weapon stage: none → 1, wood → 2, stone → 3, iron → 4, diamond/netherite → 5
     * (clamped at {@link #MAX_LEVEL}). Derived from
     * {@link ProceduralTiers#dominantWeaponStage} so the cap tracks the actual mob
     * weapon curve. Package-private for unit tests.
     */
    static int maxLevelForTier(int difficultyTier) {
        return Math.min(MAX_LEVEL, ProceduralTiers.dominantWeaponStage(difficultyTier) + 1);
    }

    /**
     * Roll a villager level on the same weighted scale as {@link #pickLevel}, then
     * cap it at {@link #maxLevelForTier} for the current difficulty tier. The cap is
     * a ceiling, not a rescale — rolls above it collapse to the cap. Always returns
     * a value in {@code [1, MAX_LEVEL]} since the stage mapping floors at 1.
     * Package-private for unit tests.
     */
    static int cappedLevel(RandomSource rng, int difficultyTier) {
        return Math.min(pickLevel(rng), maxLevelForTier(difficultyTier));
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
