package games.brennan.dungeontrain.perks.effects.crafter;

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
 *   <li>Cooking Time: {@value #COOK_SPEEDUP_PER_RANK_PERCENT}% faster per rank, never below
 *       {@value #COOK_FLOOR_PERCENT}% of the recipe time.</li>
 *   <li>Enchant Max: a roll that lands on an enchantment's normal max goes {@code rank} levels past it.</li>
 *   <li>Enchant Bonus: worn armour and held weapons act {@value #BONUS_LEVELS} level higher while active.</li>
 * </ul>
 */
public final class CrafterPerks {

    public static final String ENCHANT_COST = "enchant-cost";
    public static final String COOKING_TIME = "cooking-time";
    public static final String RE_ENCHANT = "re-enchant";
    public static final String ENCHANT_MAX = "enchant-max";
    public static final String ENCHANT_BONUS = "enchant-bonus";

    /** Cooking speed-up per rank, in percent of the recipe's cook time. */
    public static final int COOK_SPEEDUP_PER_RANK_PERCENT = 20;
    /** Cook time never drops below this share of the recipe time, whatever the rank. */
    public static final int COOK_FLOOR_PERCENT = 25;
    /** Lowest level requirement / price the Enchant Cost perk can reach. */
    public static final int MIN_COST = 1;
    /** Vanilla's anvil "Too Expensive!" threshold: a price at or above this is refused outside creative. */
    public static final int ANVIL_CAP = 40;
    /** Levels added by Enchant Bonus while the perk has any active points. */
    public static final int BONUS_LEVELS = 1;

    private CrafterPerks() {}

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

    /** Cooking Time: ticks a recipe takes at {@code rank}; at least 1 and at least the floor share. */
    public static int cookTicks(int baseTicks, int rank) {
        if (baseTicks <= 0 || rank <= 0) return baseTicks;
        int percent = Math.max(COOK_FLOOR_PERCENT, 100 - COOK_SPEEDUP_PER_RANK_PERCENT * rank);
        return Math.max(1, Math.round(baseTicks * percent / 100f));
    }

    /** Enchant Max: how many levels past the vanilla max a max-level roll climbs. */
    public static int maxLevelBonus(int rank) {
        return Math.max(0, rank);
    }

    /**
     * Enchant Max: the level a table roll ends at. Only a roll that landed exactly on the vanilla max
     * climbs; lower rolls are untouched, so the perk widens the top of the range rather than lifting
     * every result.
     */
    public static int rolledLevel(int rolled, int vanillaMax, int bonus) {
        return bonus > 0 && rolled == vanillaMax ? rolled + bonus : rolled;
    }

    /** Enchant Max in the anvil: the clamp for a combined level, never below what either input already has. */
    public static int anvilLevelCap(int vanillaMax, int rank, int highestOnInputs) {
        return Math.max(vanillaMax + maxLevelBonus(rank), highestOnInputs);
    }

    /** Enchant Bonus: extra levels a worn/held item acts with at {@code rank}. */
    public static int bonusLevels(int rank) {
        return rank > 0 ? BONUS_LEVELS : 0;
    }

    /**
     * Re-enchant switch. While {@code true}, {@link #allowsReenchant} is false at every rank and the
     * perk does nothing; the wiring and {@link #compatibleWithExisting} stay in place for when it
     * comes back.
     */
    public static final boolean REENCHANT_ON_HOLD = true;

    /**
     * Re-enchant: whether an already enchanted item may go back on the table at {@code rank}.
     *
     * <p><b>On hold (incomplete):</b> switched off by {@link #REENCHANT_ON_HOLD}. Unfinished: a table
     * row whose rolls are all filtered out by {@link #compatibleWithExisting} still shows and costs
     * levels while adding nothing, and rank beyond 1 does nothing (there is no per-rank effect yet).
     */
    public static boolean allowsReenchant(int rank) {
        return !REENCHANT_ON_HOLD && rank > 0;
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
