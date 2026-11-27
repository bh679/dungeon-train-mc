package games.brennan.dungeontrain.perks.effects.crafter;

import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkStats.Unit;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Crafter class perk ids and the pure numbers behind them. Everything here is registry-free so it
 * can be unit tested; the mixins and event handlers in this package do the wiring.
 *
 * <p>Numbers (all flagged for balancing):
 * <ul>
 *   <li>Enchant Cost: 1 level less per rank, floor 1, table requirement and anvil price alike.</li>
 *   <li>Cooking Time: rank 1 cooks in {@value #COOK_FIRST_RANK_SHARE} of the recipe time and each rank
 *       after keeps {@value #COOK_PER_RANK_KEEP} of the last (80, 70, 62, 55, 48 ... 25% at rank 10):
 *       no floor, it never stops shrinking and never reaches 0.</li>
 *   <li>Enchant Max and Enchant Bonus: {@link #groupBonus}. Levels come in bands of {@value #GROUPS}
 *       points: each point in a band opens one more {@link EnchantGroups enchantment group} at the
 *       band's level (+1 for points 1 to 10, +2 for 11 to 20 ...), the rest stay at the band below.
 *       Enchant Max lets a max-level roll and the anvil climb that far past the normal max; Enchant
 *       Bonus makes worn and held gear act that many levels higher.</li>
 * </ul>
 */
public final class CrafterPerks {

    public static final String ENCHANT_COST = "enchant-cost";
    public static final String COOKING_TIME = "cooking-time";
    public static final String RE_ENCHANT = "re-enchant";
    public static final String ENCHANT_MAX = "enchant-max";
    public static final String ENCHANT_BONUS = "enchant-bonus";

    /** Share of the recipe time rank 1 cooks in. Balancing knob. */
    public static final double COOK_FIRST_RANK_SHARE = 0.80;
    /** Each rank past the first keeps this share of the last rank's time. Balancing knob. */
    public static final double COOK_PER_RANK_KEEP = 0.88;
    /** Lowest level requirement / price the Enchant Cost perk can reach. */
    public static final int MIN_COST = 1;
    /** Vanilla's anvil "Too Expensive!" threshold: a price at or above this is refused outside creative. */
    public static final int ANVIL_CAP = 40;
    /** Enchantment groups a band of points opens, one per point ({@link EnchantGroups}). */
    public static final int GROUPS = 10;

    private CrafterPerks() {}

    /** Re-enchant: a first point's extra level cost on a re-enchant row (+100%). Balancing knob. */
    public static final double REENCHANT_FIRST_EXTRA = 1.0;
    /** Re-enchant: each point that adds no re-enchant keeps this share of the extra cost. Balancing knob. */
    public static final double REENCHANT_EXTRA_KEEP = 0.9;
    /** Re-enchant: points per extra re-enchant on an item. Balancing knob. */
    public static final int REENCHANT_STEP = 5;

    /** Marks the built Crafter perks and registers their menu stats. */
    public static void declare() {
        BuiltPerks.markBuilt(ENCHANT_COST, COOKING_TIME, RE_ENCHANT, ENCHANT_MAX, ENCHANT_BONUS);
        PerkStats.registerStepped(RE_ENCHANT, "reenchants", r -> PerkStats.num(reenchantsAllowed(Math.max(1, r)), Unit.PLAIN));
        PerkStats.register(RE_ENCHANT, "extra_cost", r -> PerkStats.plus(Math.round(reenchantExtra(Math.max(1, r)) * 100.0), Unit.PERCENT));
        PerkStats.register(ENCHANT_COST, "level_cost", r -> PerkStats.num(-Math.max(0, r), Unit.PLAIN));
        PerkStats.register(COOKING_TIME, "cook_time", r -> PerkStats.num(cookShare(r) * 100.0, Unit.PERCENT));
        PerkStats.register(ENCHANT_MAX, "past_max", r -> PerkStats.plus(topLevel(r), Unit.PLAIN));
        PerkStats.register(ENCHANT_MAX, "works_on", r -> new PerkStats.Text(worksOn(r)));
        PerkStats.register(ENCHANT_BONUS, "bonus_level", r -> PerkStats.plus(topLevel(r), Unit.PLAIN));
        PerkStats.register(ENCHANT_BONUS, "works_on", r -> new PerkStats.Text(worksOn(r)));
    }

    /** The groups at {@link #topLevel} for the menu chip: one or two by name, then a count, then all. */
    static Component worksOn(int rank) {
        int groups = groupsAtTop(rank);
        String key = "gui.dungeontrain.perks.stat.works_on.";
        if (groups <= 0) return Component.translatable(key + "none");
        if (groups >= GROUPS) return Component.translatable(key + "all");
        if (groups > 2) return Component.translatable(key + "groups", groups);
        MutableComponent names = Component.translatable(EnchantGroups.NAME_KEY + 1);
        if (groups == 2) names.append(" + ").append(Component.translatable(EnchantGroups.NAME_KEY + 2));
        return names;
    }

    /**
     * Enchant Cost: a table row requirement or anvil price after {@code rank}. A cost of 0 (vanilla's
     * "row disabled" / "no result") stays 0; anything positive loses one level per rank, floor
     * {@value #MIN_COST}.
     */
    public static int reducedCost(int cost, int rank) {
        if (cost <= 0 || rank <= 0) return cost;
        return Math.max(MIN_COST, cost - rank);
    }

    /**
     * Enchant Cost: whether this combine gets past the anvil's "Too Expensive!" cap. Only a vanilla
     * price at or above {@value #ANVIL_CAP} that the discount brings back under it is let through;
     * a price still at the cap after the discount stays "Too Expensive!", and a price already under
     * it is an ordinary combine (nothing to lift). Rank 0 never lifts it.
     */
    public static boolean liftsTooExpensive(int vanillaCost, int rank) {
        return rank > 0 && vanillaCost >= ANVIL_CAP && reducedCost(vanillaCost, rank) < ANVIL_CAP;
    }

    /** Cooking Time: share of the recipe time a {@code rank} cook takes; 1 at rank 0. */
    public static double cookShare(int rank) {
        if (rank <= 0) return 1.0;
        return COOK_FIRST_RANK_SHARE * Math.pow(COOK_PER_RANK_KEEP, rank - 1);
    }

    /** Cooking Time: ticks a recipe takes at {@code rank}; never below 1. */
    public static int cookTicks(int baseTicks, int rank) {
        if (baseTicks <= 0 || rank <= 0) return baseTicks;
        return Math.max(1, (int) Math.round(baseTicks * cookShare(rank)));
    }

    /**
     * Enchant Max and Enchant Bonus: the levels an enchantment in group {@code group} (0 = first, -1 =
     * none) gets at {@code rank}. Point {@code n} of a band opens group {@code n} at the band's level;
     * the groups it hasn't reached yet stay a level lower. 0 at rank 0 and for an enchantment in no group.
     */
    public static int groupBonus(int rank, int group) {
        if (rank <= 0 || group < 0) return 0;
        int band = (rank - 1) / GROUPS;
        int opened = (rank - 1) % GROUPS;
        return group <= opened ? band + 1 : band;
    }

    /** The highest level {@link #groupBonus} gives at {@code rank}: +1 for ranks 1 to 10, +2 for 11 to 20 ... */
    public static int topLevel(int rank) {
        return rank <= 0 ? 0 : (rank - 1) / GROUPS + 1;
    }

    /** How many groups are at {@link #topLevel} at {@code rank} (1 to {@value #GROUPS}). */
    public static int groupsAtTop(int rank) {
        return rank <= 0 ? 0 : (rank - 1) % GROUPS + 1;
    }

    /**
     * Enchant Max: the level a table roll ends at. Only a roll that landed exactly on the vanilla max
     * climbs; lower rolls are untouched, so the perk widens the top of the range rather than lifting
     * every result.
     */
    public static int rolledLevel(int rolled, int vanillaMax, int bonus) {
        return bonus > 0 && rolled == vanillaMax ? rolled + bonus : rolled;
    }

    /**
     * Enchant Max in the anvil: the clamp for a combined level, {@code bonus} past the vanilla max
     * ({@link #groupBonus} for the enchantment) and never below what either input already has.
     */
    public static int anvilLevelCap(int vanillaMax, int bonus, int highestOnInputs) {
        return Math.max(vanillaMax + Math.max(0, bonus), highestOnInputs);
    }

    /** Re-enchant: how many times one item may be re-enchanted at {@code rank}: {@code 1 + rank / 5}, 0 at rank 0. */
    public static int reenchantsAllowed(int rank) {
        return rank <= 0 ? 0 : 1 + rank / REENCHANT_STEP;
    }

    /** Re-enchant: whether an item re-enchanted {@code used} times already may go back on the table at {@code rank}. */
    public static boolean allowsReenchant(int rank, int used) {
        return Math.max(0, used) < reenchantsAllowed(rank);
    }

    /**
     * Re-enchant: the extra share of levels a re-enchant row costs at {@code rank} (1.0 = +100%). Starts
     * at {@value #REENCHANT_FIRST_EXTRA} and keeps {@value #REENCHANT_EXTRA_KEEP} of itself on every point
     * past the first that does not add a re-enchant, so each point moves exactly one of the two chips.
     * 0 at rank 0.
     */
    public static double reenchantExtra(int rank) {
        if (rank <= 0) return 0.0;
        int shrinkingPoints = (rank - 1) - rank / REENCHANT_STEP;
        return REENCHANT_FIRST_EXTRA * Math.pow(REENCHANT_EXTRA_KEEP, shrinkingPoints);
    }

    /** Re-enchant: a re-enchant row's level requirement from its fresh price; 0 (row disabled) stays 0. */
    public static int reenchantCost(int cost, int rank) {
        if (cost <= 0 || rank <= 0) return cost;
        return (int) Math.ceil(cost * (1.0 + reenchantExtra(rank)) - 1e-9);
    }

    /**
     * Re-enchant: drop rolled candidates that clash with an enchantment already on the item, so a
     * Sharpness sword cannot pick up Smite. Pure: {@code compatible} decides a pair. Returns a new list.
     */
    public static <R, E> List<R> compatibleWithExisting(List<R> rolled, Collection<E> existing,
                                                        BiPredicate<R, E> compatible) {
        if (rolled == null) return List.of();
        if (existing == null || existing.isEmpty()) return new ArrayList<>(rolled);
        List<R> kept = new ArrayList<>(rolled.size());
        for (R candidate : rolled) {
            boolean clash = false;
            for (E have : existing) {
                if (!compatible.test(candidate, have)) { clash = true; break; }
            }
            if (!clash) kept.add(candidate);
        }
        return kept;
    }
}
