package games.brennan.dungeontrain.perks.effects.knight;

import net.minecraft.util.RandomSource;

/**
 * The Knight's tier 2/3 combat perks: ids and the pure maths behind them. Rank is the perk's active
 * points this life (see {@code Perks.rank}); every method returns the "off" value at rank 0.
 *
 * <p>Crits and Vampirism share an "overflow" curve: a percentage that grows with rank, where every
 * full 100% is a guaranteed step and the remainder is the chance of one step more. So there is no
 * cap: past 100% the perk keeps climbing towards the next step.
 */
public final class KnightPerks {

    /** Tier 2: rank% chance a melee hit crits; past 100, rank-100 % chance of a harder crit. */
    public static final String CRITS = "crits";
    /** Tier 3: kills grant Strength; 3 s, each rank adding half the time the last did, one level stronger (time back to 3 s) every 10 ranks. */
    public static final String KILL_BUFF = "kill-buff";
    /** Tier 3: kills have a chance to heal half a heart; the chance overflows into extra halves. */
    public static final String VAMPIRISM = "vampirism";

    /** Vanilla's critical-hit multiplier: a jump crit, and the first crit step. */
    public static final float VANILLA_CRIT_MULTIPLIER = 1.5f;
    /** Each crit step past the first adds this much to the multiplier (1.5x, 2x, 2.5x ...). */
    static final float CRIT_MULTIPLIER_PER_STEP = 0.5f;
    static final int CRIT_PERCENT_PER_RANK = 1;

    static final int STRENGTH_BASE_TICKS = 60;
    static final int RANKS_PER_STRENGTH_LEVEL = 10;

    static final int VAMPIRISM_BASE_PERCENT = 25;
    static final int VAMPIRISM_PERCENT_PER_RANK = 5;
    static final float HALF_HEART = 1.0f;

    private KnightPerks() {}

    /**
     * Rolls an overflow percentage: {@code percent / 100} guaranteed steps plus a
     * {@code percent % 100} chance of one more.
     */
    static int rollSteps(int percent, RandomSource random) {
        if (percent <= 0) return 0;
        int steps = percent / 100;
        int remainder = percent % 100;
        if (remainder > 0 && random.nextInt(100) < remainder) steps++;
        return steps;
    }

    /** Crit percentage at {@code rank}: 1% per rank, uncapped. */
    public static int critPercent(int rank) {
        return Math.max(0, rank) * CRIT_PERCENT_PER_RANK;
    }

    /** Damage multiplier for {@code steps} crit steps; 1 (no crit) at 0 steps. */
    public static float critMultiplier(int steps) {
        if (steps <= 0) return 1f;
        return VANILLA_CRIT_MULTIPLIER + (steps - 1) * CRIT_MULTIPLIER_PER_STEP;
    }

    /** One crit roll at {@code rank}: the multiplier to apply, 1 for no perk crit. */
    public static float rollCritMultiplier(int rank, RandomSource random) {
        return critMultiplier(rollSteps(critPercent(rank), random));
    }

    /** Strength level index (0 = Strength I) at {@code rank}: up one every 10 ranks. */
    public static int strengthAmplifier(int rank) {
        if (rank <= 0) return 0;
        return (rank - 1) / RANKS_PER_STRENGTH_LEVEL;
    }

    /**
     * Strength duration per kill, in ticks: 3 s at the first rank of a Strength level, and each rank
     * after adds half what the one before did (+1.5 s, +0.75 s ...), so a level approaches 6 s. Each
     * new level starts back at 3 s.
     */
    public static int strengthDurationTicks(int rank) {
        if (rank <= 0) return 0;
        int step = (rank - 1) % RANKS_PER_STRENGTH_LEVEL;
        return (int) Math.round(STRENGTH_BASE_TICKS * (2.0 - Math.pow(0.5, step)));
    }

    /**
     * Whether a fresh Strength grant should replace the one running: a stronger level always wins,
     * the same level only ever extends, and a weaker grant never touches a stronger buff.
     */
    public static boolean shouldRefreshStrength(int currentAmplifier, int remainingTicks,
                                                int newAmplifier, int newTicks) {
        if (newTicks <= 0) return false;
        if (remainingTicks <= 0 || newAmplifier > currentAmplifier) return true;
        return newAmplifier == currentAmplifier && newTicks > remainingTicks;
    }

    /** Vampirism percentage at {@code rank}: 25%, +5% per rank past the first, uncapped. */
    public static int vampirismPercent(int rank) {
        if (rank <= 0) return 0;
        return VAMPIRISM_BASE_PERCENT + (rank - 1) * VAMPIRISM_PERCENT_PER_RANK;
    }

    /** One Vampirism roll at {@code rank}: health to heal (half a heart per step). */
    public static float rollVampirismHeal(int rank, RandomSource random) {
        return rollSteps(vampirismPercent(rank), random) * HALF_HEART;
    }
}
