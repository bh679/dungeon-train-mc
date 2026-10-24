package games.brennan.dungeontrain.perks.effects.knight;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * The Knight's tier 2/3 combat perks: ids and the pure maths behind them. Rank is the perk's active
 * points this life (see {@code Perks.rank}); every method returns the "off" value at rank 0.
 */
public final class KnightPerks {

    /** Tier 2: 1% chance per rank of a critical hit on every melee hit, on top of vanilla's jump crit. */
    public static final String CRITS = "crits";
    /** Tier 3: kills grant Strength I for 2 s per rank and heal half a heart. */
    public static final String KILL_BUFF = "kill-buff";
    /** Tier 3: kills heal half a heart per rank. */
    public static final String VAMPIRISM = "vampirism";

    /** Vanilla's critical-hit multiplier; a perk crit hits exactly as hard as a jump crit. */
    public static final float CRIT_DAMAGE_MULTIPLIER = 1.5f;
    /** Strength I. */
    public static final int STRENGTH_AMPLIFIER = 0;

    static final int CRIT_PERCENT_PER_RANK = 1;
    static final int STRENGTH_TICKS_PER_RANK = 40;
    static final float HALF_HEART = 1.0f;

    private KnightPerks() {}

    /** Chance in [0, 1] of a perk crit at {@code rank}. */
    public static float critChance(int rank) {
        return Mth.clamp(rank * CRIT_PERCENT_PER_RANK, 0, 100) / 100f;
    }

    /** One roll for a perk crit. Never true at rank 0, always true at rank 100. */
    public static boolean rollCrit(int rank, RandomSource random) {
        if (rank <= 0) return false;
        float chance = critChance(rank);
        return chance >= 1f || random.nextFloat() < chance;
    }

    /** Strength I duration granted per kill, in ticks. */
    public static int strengthDurationTicks(int rank) {
        return Math.max(0, rank) * STRENGTH_TICKS_PER_RANK;
    }

    /** Kill Buff's flat heal: half a heart when active. */
    public static float killBuffHeal(int rank) {
        return rank > 0 ? HALF_HEART : 0f;
    }

    /** Vampirism's heal: half a heart per rank. */
    public static float vampirismHeal(int rank) {
        return Math.max(0, rank) * HALF_HEART;
    }

    /**
     * Whether a fresh Strength grant of {@code newTicks} should replace one with {@code remainingTicks}
     * left: a kill only ever extends the buff, never shortens it.
     */
    public static boolean shouldRefreshStrength(int remainingTicks, int newTicks) {
        return newTicks > 0 && newTicks > remainingTicks;
    }
}
