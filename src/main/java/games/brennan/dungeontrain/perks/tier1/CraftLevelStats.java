package games.brennan.dungeontrain.perks.tier1;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemstats.api.StatsModifier;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.difficulty.ItemStatLevelScaling;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.TierOnePerks;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

/**
 * Craft Level Stats (Crafter): gear you craft gets stats measured against the chest loot of the
 * level you are in. The loot roller stamps chest gear at the full level tier with
 * {@code StatsModifier.applyStats(stack, rng, primaryStatBonus(stack, levelTier))}, whatever its
 * material; this does the same on {@code ItemCraftedEvent}, at a lower <em>rolled</em> tier:
 * <pre>rolledTier = floor(levelTier * q * ceiling(rank)),  q = best of (1 + rank) uniforms
 * ceiling(rank) = 1 - 0.5 * 0.9^(rank - 1)</pre>
 * The ceiling starts at half the chest tier and each rank closes a tenth of the remaining gap, so
 * a crafted item never reaches the level's chest-loot tier but every rank gets closer.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CraftLevelStats {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Ceiling at rank 1, as a share of the chest-loot tier. */
    public static final double CEILING_AT_RANK_ONE = 0.5;
    /** Share of the remaining gap to chest loot each further rank leaves open. */
    public static final double CEILING_GAP_KEPT_PER_RANK = 0.9;

    private CraftLevelStats() {}

    // ---------------- pure maths ----------------

    /** Best of {@code 1 + rank} uniform draws: rises toward 1 with rank. */
    public static double qualityRoll(int rank, RandomSource rng) {
        double best = 0;
        for (int i = 0; i <= Math.max(0, rank); i++) best = Math.max(best, rng.nextDouble());
        return best;
    }

    /** Highest share of the chest-loot tier reachable at {@code rank}; 0 at rank 0, always below 1. */
    public static double ceiling(int rank) {
        if (rank <= 0) return 0;
        return 1.0 - (1.0 - CEILING_AT_RANK_ONE) * Math.pow(CEILING_GAP_KEPT_PER_RANK, rank - 1);
    }

    /** The tier the crafted item is stamped at; always below {@code levelTier}. */
    public static int rolledTier(int levelTier, double quality, int rank) {
        if (levelTier <= 0) return 0;
        double q = Math.max(0, Math.min(1, quality)) * ceiling(rank);
        return Math.min(levelTier - 1, (int) Math.floor(levelTier * q));
    }

    /** Gear the loot roller would give stats to: tools, weapons and armour. */
    public static boolean isGear(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof TieredItem || stack.getItem() instanceof ArmorItem);
    }

    // ---------------- seams ----------------

    /** The difficulty tier of the carriage the player stands in, falling back to their travelled tier. */
    public static int levelTierFor(ServerPlayer player) {
        Integer pIdx = TrainCarriageAppender.lastCarriageIndex(player.getUUID());
        if (pIdx != null) return DifficultyProgression.positionTier(pIdx);
        int travelled = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).travelledCarriageIndex();
        return DifficultyProgression.tierForTravelled(DifficultyProgression.effectiveTravelled(travelled));
    }

    /** Stamp {@code stack} as if looted at {@code tier}. Returns the tier applied (0 = untouched). */
    public static int stamp(ItemStack stack, int tier, RandomSource rng) {
        if (tier <= 0 || !isGear(stack)) return 0;
        // applyStats scales from the stack's current modifiers; a fresh craft has none, but a
        // repeat stamp (e.g. a re-crafted item) must start clean, the way BakedItemStats rerolls.
        stack.remove(DataComponents.ATTRIBUTE_MODIFIERS);
        StatsModifier.applyStats(stack, rng, ItemStatLevelScaling.primaryStatBonus(stack, tier));
        return tier;
    }

    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack stack = event.getCrafting();
        if (!isGear(stack)) return;
        int rank = Perks.rank(player, TierOnePerks.CRAFT_LEVEL_STATS);
        if (rank <= 0) return;
        int levelTier = levelTierFor(player);
        if (levelTier <= 0) return;
        RandomSource rng = player.getRandom();
        int tier = rolledTier(levelTier, qualityRoll(rank, rng), rank);
        int applied = stamp(stack, tier, rng);
        LOGGER.debug("[DungeonTrain] Craft Level Stats rank {}: {} at level tier {} -> stamped tier {} for {}",
            rank, BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(), levelTier, applied,
            player.getScoreboardName());
    }
}
