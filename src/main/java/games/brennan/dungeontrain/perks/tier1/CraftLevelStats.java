package games.brennan.dungeontrain.perks.tier1;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemstats.api.StatsModifier;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.difficulty.ItemStatLevelScaling;
import games.brennan.dungeontrain.net.CraftLevelTierPacket;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.TierOnePerks;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Craft Level Stats (Crafter): gear you craft gets stats measured against the chest loot of the
 * level you are in. The loot roller stamps chest gear at the full level tier with
 * {@code StatsModifier.applyStats(stack, rng, primaryStatBonus(stack, levelTier))}, whatever its
 * material; this does the same on {@code ItemCraftedEvent}, at a lower <em>rolled</em> tier:
 * <pre>rolledTier = floor(levelTier * q * ceiling(rank)),  q = best of (1 + rank) uniforms
 * ceiling(rank) = 1 - 0.5 * 0.9^(rank - 1)</pre>
 * The ceiling starts at half the chest tier and each rank closes a tenth of the remaining gap, so
 * a crafted item never reaches the level's chest-loot tier but every rank gets closer.
 *
 * <p>Never worse than vanilla: every stat AIS rolls is floored at the item's own vanilla value
 * ({@link #floored}), so a bad roll just leaves that stat as crafted. The crafting result slot
 * previews the range ({@link #statRanges}, drawn by {@code client.CraftLevelStatsTooltip}); the
 * server sends the level tier it would use ({@link CraftLevelTierPacket}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CraftLevelStats {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Ceiling at rank 1, as a share of the chest-loot tier. */
    public static final double CEILING_AT_RANK_ONE = 0.5;
    /** Share of the remaining gap to chest loot each further rank leaves open. */
    public static final double CEILING_GAP_KEPT_PER_RANK = 0.9;
    /** AIS's best stat roll (its {@code StatsRoller.MAX_FACTOR}); the range preview's top end. */
    public static final double BEST_ROLL_FACTOR = 1.5;
    /** The player base AIS adds before scaling attack damage / attack speed (its own constants). */
    static final double PLAYER_BASE_ATTACK_DAMAGE = 1.0;
    static final double PLAYER_BASE_ATTACK_SPEED = 4.0;
    /** How often (ticks) the level tier is re-checked for the client preview. */
    private static final int TIER_SYNC_INTERVAL = 20;
    private static final Map<UUID, Integer> LAST_SENT_TIER = new ConcurrentHashMap<>();

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

    /**
     * Average share of the chest-loot tier at {@code rank}, before the tier is floored: the best of
     * {@code 1 + rank} uniform draws averages {@code (r + 1) / (r + 2)}, times the {@link #ceiling}.
     */
    public static double averageShare(int rank) {
        if (rank <= 0) return 0;
        return ceiling(rank) * (rank + 1) / (rank + 2.0);
    }

    /** The tier the crafted item is stamped at; always below {@code levelTier}. */
    public static int rolledTier(int levelTier, double quality, int rank) {
        if (levelTier <= 0) return 0;
        double q = Math.max(0, Math.min(1, quality)) * ceiling(rank);
        return Math.min(levelTier - 1, (int) Math.floor(levelTier * q));
    }

    /** The best tier a craft can be stamped at: {@link #rolledTier} with a perfect quality roll. */
    public static int maxTier(int levelTier, int rank) {
        return rolledTier(levelTier, 1.0, rank);
    }

    /** A rolled modifier amount floored at its vanilla amount: never worse than an unperked craft. */
    public static double floorAmount(double rolled, double vanilla) {
        return Math.max(rolled, vanilla);
    }

    /**
     * Highest modifier amount the stamp can give: AIS's best roll on the effective value
     * ({@code (base + amount) * factor - base}) plus the flat bonus on the primary stat, floored at vanilla.
     */
    public static double maxAmount(double playerBase, double vanillaAmount, boolean primaryStat, double primaryStatBonus) {
        double rolled = (playerBase + vanillaAmount) * BEST_ROLL_FACTOR - playerBase;
        return floorAmount(primaryStat ? rolled + primaryStatBonus : rolled, vanillaAmount);
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

    /** Stamp {@code stack} as if looted at {@code tier}, never below vanilla. Returns the tier applied (0 = untouched). */
    public static int stamp(ItemStack stack, int tier, RandomSource rng) {
        if (tier <= 0 || !isGear(stack)) return 0;
        ItemAttributeModifiers vanilla = vanillaModifiers(stack);
        if (vanilla.modifiers().isEmpty()) return 0;
        // Start from the item's own vanilla stats (a repeat stamp must start clean, the way
        // BakedItemStats rerolls). Setting them rather than removing the component matters: a
        // sword keeps its stats only in that component, and AIS reads nothing once it is gone.
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, vanilla);
        StatsModifier.applyStats(stack, rng, ItemStatLevelScaling.primaryStatBonus(stack, tier));
        ItemAttributeModifiers rolled = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, vanilla);
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, floored(vanilla, rolled));
        return tier;
    }

    /** The item's unperked modifiers: its default component, else the item's own defaults (armour). */
    public static ItemAttributeModifiers vanillaModifiers(ItemStack stack) {
        ItemAttributeModifiers proto = stack.getItem().components()
            .getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        return proto.modifiers().isEmpty() ? stack.getItem().getDefaultAttributeModifiers(stack) : proto;
    }

    /** {@code rolled} with every modifier raised to at least its vanilla amount (same attribute + id). */
    public static ItemAttributeModifiers floored(ItemAttributeModifiers vanilla, ItemAttributeModifiers rolled) {
        List<ItemAttributeModifiers.Entry> out = new ArrayList<>(rolled.modifiers().size());
        for (ItemAttributeModifiers.Entry entry : rolled.modifiers()) {
            AttributeModifier mod = entry.modifier();
            double floor = vanillaAmount(vanilla, entry.attribute(), mod).orElse(Double.NEGATIVE_INFINITY);
            double amount = floorAmount(mod.amount(), floor);
            out.add(amount == mod.amount() ? entry : new ItemAttributeModifiers.Entry(entry.attribute(),
                new AttributeModifier(mod.id(), amount, mod.operation()), entry.slot()));
        }
        return new ItemAttributeModifiers(out, rolled.showInTooltip());
    }

    private static java.util.OptionalDouble vanillaAmount(ItemAttributeModifiers vanilla, Holder<Attribute> attr, AttributeModifier mod) {
        for (ItemAttributeModifiers.Entry e : vanilla.modifiers()) {
            if (same(e.attribute(), attr) && e.modifier().id().equals(mod.id())) {
                return java.util.OptionalDouble.of(e.modifier().amount());
            }
        }
        return java.util.OptionalDouble.empty();
    }

    /** What a crafted stat can land on, as vanilla's tooltip shows it (player base included). */
    public record StatRange(double min, double max) {}

    /**
     * Per attribute the stamp varies, the range a craft of {@code stack} can roll at this level and
     * rank: vanilla to the best roll. Empty when the craft would be left untouched. Shown in the
     * crafting result slot by the client tooltip.
     */
    public static Map<Holder<Attribute>, StatRange> statRanges(ItemStack stack, int levelTier, int rank) {
        Map<Holder<Attribute>, StatRange> out = new LinkedHashMap<>();
        if (rank <= 0 || !isGear(stack)) return out;
        int tier = maxTier(levelTier, rank);
        if (tier <= 0) return out;
        double bonus = ItemStatLevelScaling.primaryStatBonus(stack, tier);
        for (ItemAttributeModifiers.Entry e : vanillaModifiers(stack).modifiers()) {
            Holder<Attribute> attr = e.attribute();
            if (!isRolledStat(attr) || e.modifier().operation() != AttributeModifier.Operation.ADD_VALUE) continue;
            double base = playerBaseFor(attr);
            double vanillaAmt = e.modifier().amount();
            boolean primary = same(attr, Attributes.ATTACK_DAMAGE) || same(attr, Attributes.ARMOR);
            out.put(attr, new StatRange(base + vanillaAmt, base + maxAmount(base, vanillaAmt, primary, bonus)));
        }
        return out;
    }

    private static boolean same(Holder<Attribute> a, Holder<Attribute> b) {
        return a.value() == b.value();
    }

    private static boolean isRolledStat(Holder<Attribute> attr) {
        return same(attr, Attributes.ATTACK_DAMAGE) || same(attr, Attributes.ATTACK_SPEED)
            || same(attr, Attributes.ARMOR) || same(attr, Attributes.ARMOR_TOUGHNESS);
    }

    private static double playerBaseFor(Holder<Attribute> attr) {
        if (same(attr, Attributes.ATTACK_DAMAGE)) return PLAYER_BASE_ATTACK_DAMAGE;
        if (same(attr, Attributes.ATTACK_SPEED)) return PLAYER_BASE_ATTACK_SPEED;
        return 0.0;
    }

    /** Keeps the client's level tier current for the result-slot preview; only players with the perk. */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % TIER_SYNC_INTERVAL != 0) return;
        if (Perks.rank(player, TierOnePerks.CRAFT_LEVEL_STATS) <= 0) return;
        int tier = Math.max(0, levelTierFor(player));
        Integer last = LAST_SENT_TIER.put(player.getUUID(), tier);
        if (last == null || last != tier) PacketDistributor.sendToPlayer(player, new CraftLevelTierPacket(tier));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT_TIER.remove(event.getEntity().getUUID());
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
