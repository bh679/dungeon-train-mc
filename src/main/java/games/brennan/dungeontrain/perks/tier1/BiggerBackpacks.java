package games.brennan.dungeontrain.perks.tier1;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.TierOnePerks;
import games.brennan.ediblebackpacks.config.EBConfig;
import games.brennan.ediblebackpacks.item.EdibleBackpackItem;
import games.brennan.ediblebackpacks.network.EBNetwork;
import games.brennan.ediblebackpacks.registry.ModAttachments;
import games.brennan.ediblebackpacks.storage.BackpackData;
import games.brennan.ediblebackpacks.storage.BackpackPolicy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import org.slf4j.Logger;

/**
 * Bigger Backpacks (Hoarder): "apples have a 25% chance to be worth 2, +25% per rank". In this
 * mod the apples are the edible backpacks (a backpack is an apple you ate), so a bite that would
 * grant {@code n} slots grants {@code n * (1 + extra)} where {@code extra} is rolled from
 * {@code p = 0.25 * rank}: rank 1 doubles the bite one time in four, rank 4 always doubles, rank
 * 8 always triples. The extra is added on top of the mod's own grant, clamped to the slot cap,
 * and the slot count is re-synced to the client.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BiggerBackpacks {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Chance of one extra copy per rank. */
    public static final double CHANCE_PER_RANK = 0.25;

    private BiggerBackpacks() {}

    /** Expected extra copies at {@code rank}: {@code 0.25 * rank}. */
    public static double extraExpectation(int rank) {
        return Math.max(0, rank) * CHANCE_PER_RANK;
    }

    /**
     * Extra copies for one bite: the whole part of the expectation always, the fractional part as
     * a single roll. {@code roll} is a uniform in [0,1).
     */
    public static int extraCopies(int rank, double roll) {
        double p = extraExpectation(rank);
        int whole = (int) Math.floor(p);
        double frac = p - whole;
        return whole + (roll < frac ? 1 : 0);
    }

    public static int extraCopies(int rank, RandomSource rng) {
        return extraCopies(rank, rng.nextDouble());
    }

    @SubscribeEvent
    public static void onUseItemFinish(LivingEntityUseItemEvent.Finish event) {
        if (event.getEntity().level().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getItem().getItem() instanceof EdibleBackpackItem backpack)) return;
        int rank = Perks.rank(player, TierOnePerks.BIGGER_BACKPACKS);
        if (rank <= 0) return;
        int cap = EBConfig.maxSlots();
        if (cap <= 0) return;
        int extra = extraCopies(rank, player.getRandom()) * backpack.slotsGranted();
        if (extra <= 0) return;
        BackpackData data = player.getData(ModAttachments.BACKPACK);
        int before = data.unlocked();
        int after = BackpackPolicy.clampUnlocked(before + extra, cap);
        if (after == before) return;
        data.setUnlocked(after);
        EBNetwork.syncSlotCount(player);
        LOGGER.debug("[DungeonTrain] Bigger Backpacks rank {}: +{} slots for {} ({} -> {})",
            rank, extra, player.getScoreboardName(), before, after);
    }
}
