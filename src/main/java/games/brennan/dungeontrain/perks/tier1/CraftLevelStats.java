package games.brennan.dungeontrain.perks.tier1;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemstats.api.StatsModifier;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.difficulty.ItemStatLevelScaling;
import games.brennan.dungeontrain.difficulty.ProceduralTiers;
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
 * Craft Level Stats (Crafter): gear you craft gets the stats of the level you are in, as if it
 * had been looted there. The loot roller stamps chest gear with
 * {@code StatsModifier.applyStats(stack, rng, primaryStatBonus(stack, levelTier))}; this does the
 * same on {@code ItemCraftedEvent}, but at a <em>rolled</em> tier:
 * <pre>rolledTier = round(levelTier * q),  q = max of (1 + rank) uniforms * 0.5^materialGap</pre>
 * so more rank pushes the roll toward the level's full tier, and crafting below the level's
 * dominant material (an iron sword where mobs carry diamond) halves the roll per material step.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CraftLevelStats {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Roll factor per material step the crafted item sits below the level's dominant material. */
    public static final double MATERIAL_STEP_FACTOR = 0.5;

    private CraftLevelStats() {}

    // ---------------- pure maths ----------------

    /** Best of {@code 1 + rank} uniform draws: the "chance of the max" rises with rank. */
    public static double qualityRoll(int rank, RandomSource rng) {
        double best = 0;
        for (int i = 0; i <= Math.max(0, rank); i++) best = Math.max(best, rng.nextDouble());
        return best;
    }

    public static double materialFactor(int materialGap) {
        return Math.pow(MATERIAL_STEP_FACTOR, Math.max(0, materialGap));
    }

    /** The tier the crafted item is stamped at. {@code quality} is {@link #qualityRoll}. */
    public static int rolledTier(int levelTier, double quality, int materialGap) {
        if (levelTier <= 0) return 0;
        double q = Math.max(0, Math.min(1, quality)) * materialFactor(materialGap);
        return (int) Math.round(levelTier * q);
    }

    /**
     * Material stage of an item by its id, on the mob-weapon scale ({@code ProceduralTiers
     * .dominantWeaponStage}): 1 wood/leather/gold, 2 stone/turtle, 3 iron/chainmail, 4 diamond,
     * 5 netherite; anything unrecognised counts as iron so modded gear is neither punished nor
     * favoured.
     */
    public static int materialStage(String itemPath) {
        String p = itemPath == null ? "" : itemPath;
        if (p.contains("netherite")) return 5;
        if (p.contains("diamond")) return 4;
        if (p.contains("iron") || p.contains("chainmail")) return 3;
        if (p.contains("stone") || p.contains("turtle")) return 2;
        if (p.contains("wooden") || p.contains("leather") || p.contains("golden")) return 1;
        return 3;
    }

    public static int materialGap(int levelTier, String itemPath) {
        return Math.max(0, ProceduralTiers.dominantWeaponStage(levelTier) - materialStage(itemPath));
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
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        RandomSource rng = player.getRandom();
        int tier = rolledTier(levelTier, qualityRoll(rank, rng), materialGap(levelTier, path));
        int applied = stamp(stack, tier, rng);
        LOGGER.debug("[DungeonTrain] Craft Level Stats rank {}: {} at level tier {} -> stamped tier {} for {}",
            rank, path, levelTier, applied, player.getScoreboardName());
    }
}
