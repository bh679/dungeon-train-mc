package games.brennan.dungeontrain.perks.effects.tank;

import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.PerkEffects;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkStats.Unit;

/**
 * The Tank class's tier 2/3 perks: ids and the pure numbers behind them. Rank is the perk's active
 * points this life ({@code Perks.rank}); every method returns "no effect" at rank 0. The handlers
 * ({@link TankArmourPerk}, {@link TankHungerPerk}, {@link TankHealingPerk}, {@link TankTotemPerk})
 * read their rank and apply these numbers.
 *
 * <p>Curves from the perks review (2026-10-27): Hunger, Healing and Totem shrink by a fixed share per
 * point, so they keep improving and never reach zero; Armour is a flat +2 per point.</p>
 */
public final class TankPerks {

    /** Tier 2: +{@value #ARMOUR_PER_RANK} armour points per rank. */
    public static final String ARMOUR = "armour";
    /** Tier 2: hunger drains at {@value #HUNGER_KEEP_PER_RANK}^rank of the usual rate. */
    public static final String HUNGER = "hunger";
    /** Tier 3: half a heart every {@value #HEAL_BASE_SECONDS} × {@value #HEAL_KEEP_PER_RANK}^(rank−1) seconds. */
    public static final String HEALING = "healing";
    /** Tier 3: a totem pop without the item, on a {@value #TOTEM_BASE_MINUTES} × {@value #TOTEM_KEEP_PER_RANK}^rank minute cooldown. */
    public static final String TOTEM = "totem";

    static final int TICKS_PER_SECOND = 20;

    static final double ARMOUR_PER_RANK = 2.0;

    static final double HUNGER_KEEP_PER_RANK = 0.95;
    /** Vanilla {@code FoodData.tick} subtracts this once exhaustion crosses it. */
    static final float EXHAUSTION_WRAP = 4.0F;

    static final double HEAL_BASE_SECONDS = 120.0;
    static final double HEAL_KEEP_PER_RANK = 0.85;
    /** Half a heart. */
    static final float HEAL_AMOUNT = 1.0F;

    static final double TOTEM_BASE_MINUTES = 30.0;
    static final double TOTEM_KEEP_PER_RANK = 0.93;

    private TankPerks() {}

    /** Marks the built Tank perks, wires Armour's re-apply and registers the menu stats. */
    public static void declare() {
        BuiltPerks.markBuilt(ARMOUR, HUNGER, HEALING, TOTEM);
        PerkEffects.register(new TankArmourPerk());
        PerkStats.register(ARMOUR, "armour", r -> PerkStats.plus(armourBonus(r), Unit.ARMOUR));
        PerkStats.register(HUNGER, "hunger_drain", r -> PerkStats.num(-hungerRefundFraction(r) * 100, Unit.PERCENT));
        PerkStats.register(HEALING, "heals_every", r -> PerkStats.num(healIntervalSeconds(Math.max(1, r)), Unit.SECONDS));
        PerkStats.register(TOTEM, "cooldown", r -> PerkStats.num(totemCooldownMinutes(r), Unit.MINUTES));
    }

    /** Flat armour points added to the player for {@code rank}; 0 at rank 0. */
    public static double armourBonus(int rank) {
        if (rank <= 0) return 0.0;
        return ARMOUR_PER_RANK * rank;
    }

    /** Share of every exhaustion tick handed back: {@code 1 − 0.95^rank}, so −5%, −10%, … −40% at 10; never all of it. */
    public static double hungerRefundFraction(int rank) {
        if (rank <= 0) return 0.0;
        return 1.0 - Math.pow(HUNGER_KEEP_PER_RANK, rank);
    }

    /**
     * Exhaustion vanilla spent between two consecutive reads. Vanilla only ever adds exhaustion, and wraps
     * by subtracting {@value #EXHAUSTION_WRAP} once it crosses that, so a drop is the wrap, not a refund.
     * Reads that are not finite, or a drop bigger than one wrap (a reset from outside), count as nothing.
     */
    static float exhaustionSpent(float previous, float current) {
        if (!Float.isFinite(previous) || !Float.isFinite(current)) return 0.0F;
        float delta = current - previous;
        if (delta >= 0.0F) return delta;
        float wrapped = delta + EXHAUSTION_WRAP;
        return wrapped >= 0.0F ? wrapped : 0.0F;
    }

    /** Seconds between heals: 120, 102, 87, 74, 63 at rank 5, 28 at 10, shrinking on; 0 when inactive. */
    public static double healIntervalSeconds(int rank) {
        if (rank <= 0) return 0.0;
        return HEAL_BASE_SECONDS * Math.pow(HEAL_KEEP_PER_RANK, rank - 1);
    }

    /** {@link #healIntervalSeconds} in ticks, at least one; 0 when inactive. */
    static int healIntervalTicks(int rank) {
        if (rank <= 0) return 0;
        return Math.max(1, (int) Math.round(healIntervalSeconds(rank) * TICKS_PER_SECOND));
    }

    /** Totem cooldown in minutes: 27.9, 25.9, 24.1, … 14.5 at rank 10, shrinking on; 0 when inactive. */
    public static double totemCooldownMinutes(int rank) {
        if (rank <= 0) return 0.0;
        return TOTEM_BASE_MINUTES * Math.pow(TOTEM_KEEP_PER_RANK, rank);
    }

    /** {@link #totemCooldownMinutes} in ticks; 0 when inactive. */
    static long totemCooldownTicks(int rank) {
        return Math.round(totemCooldownMinutes(rank) * 60.0 * TICKS_PER_SECOND);
    }
}
