package games.brennan.dungeontrain.perks.effects.tank;

/**
 * The Tank class's tier 2/3 perks: ids and the pure numbers behind them. Rank is the perk's active points
 * (from cosmic debris, per life); rank 0 means the perk is not active and every method here returns "no
 * effect". Each handler ({@link TankArmourPerk}, {@link TankHungerPerk}, {@link TankHealingPerk},
 * {@link TankTotemPerk}) reads its rank through {@code Perks.rank} and applies these numbers.
 *
 * <p>All constants are first-pass balancing values; see the Gate 2 report for the ones flagged.</p>
 */
public final class TankPerks {

    /** Tier 2: +{@value #ARMOUR_PER_RANK} armour points per rank. */
    public static final String ARMOUR = "armour";
    /** Tier 2: hunger drains {@value #HUNGER_REFUND_PER_RANK} (5%) slower per rank, capped at half. */
    public static final String HUNGER = "hunger";
    /** Tier 3: half a heart every {@value #HEAL_BASE_SECONDS} - {@value #HEAL_SECONDS_PER_RANK} * rank seconds. */
    public static final String HEALING = "healing";
    /** Tier 3: a totem pop without the item, on a cooldown that shortens with rank. */
    public static final String TOTEM = "totem";

    static final int TICKS_PER_SECOND = 20;

    static final double ARMOUR_PER_RANK = 2.0;

    static final double HUNGER_REFUND_PER_RANK = 0.05;
    static final double HUNGER_REFUND_CAP = 0.50;
    /** Vanilla {@code FoodData.tick} subtracts this once exhaustion crosses it. */
    static final float EXHAUSTION_WRAP = 4.0F;

    static final int HEAL_BASE_SECONDS = 60;
    static final int HEAL_SECONDS_PER_RANK = 5;
    static final int HEAL_FLOOR_SECONDS = 5;
    /** Half a heart. */
    static final float HEAL_AMOUNT = 1.0F;

    static final int TOTEM_BASE_MINUTES = 20;
    static final int TOTEM_MINUTES_PER_RANK = 1;
    static final int TOTEM_FLOOR_MINUTES = 10;

    private TankPerks() {}

    /** Flat armour points added to the player for {@code rank}; 0 at rank 0. */
    static double armourBonus(int rank) {
        if (rank <= 0) return 0.0;
        return ARMOUR_PER_RANK * rank;
    }

    /** Fraction of every exhaustion tick handed back, in [0, {@value #HUNGER_REFUND_CAP}]. */
    static double hungerRefundFraction(int rank) {
        if (rank <= 0) return 0.0;
        return Math.min(HUNGER_REFUND_CAP, HUNGER_REFUND_PER_RANK * rank);
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

    /** Seconds between heals for {@code rank}; 0 when inactive. Never below {@value #HEAL_FLOOR_SECONDS}. */
    static int healIntervalSeconds(int rank) {
        if (rank <= 0) return 0;
        return Math.max(HEAL_FLOOR_SECONDS, HEAL_BASE_SECONDS - HEAL_SECONDS_PER_RANK * rank);
    }

    /** {@link #healIntervalSeconds} in ticks; 0 when inactive. */
    static int healIntervalTicks(int rank) {
        return healIntervalSeconds(rank) * TICKS_PER_SECOND;
    }

    /** Totem cooldown in minutes for {@code rank}; 0 when inactive. Never below {@value #TOTEM_FLOOR_MINUTES}. */
    static int totemCooldownMinutes(int rank) {
        if (rank <= 0) return 0;
        return Math.max(TOTEM_FLOOR_MINUTES, TOTEM_BASE_MINUTES - TOTEM_MINUTES_PER_RANK * rank);
    }

    /** {@link #totemCooldownMinutes} in ticks; 0 when inactive. */
    static long totemCooldownTicks(int rank) {
        return (long) totemCooldownMinutes(rank) * 60L * TICKS_PER_SECOND;
    }
}
