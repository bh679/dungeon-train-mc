package games.brennan.dungeontrain.perks.effects.lover;

/**
 * Share Hearts numbers, in health points (two to a heart). Points 1 to 6 cost a heart and give
 * {@code 1 + floor((r - 1) / 2)}; from point 7 the cost halves and the gift restarts at a heart,
 * {@code 2 + floor((r - 7) / 2)}. Every even point adds a 50% chance of half a heart more, which the
 * next point makes certain. No cap.
 */
public final class ShareHeartsMath {

    /** The chance of the extra half heart on an even point. */
    public static final double EXTRA_CHANCE = 0.5;
    /** The giver is never taken below one heart. */
    public static final float GIVER_FLOOR = 2.0F;
    /** Ticks before the same giver can give the same target again. */
    public static final int COOLDOWN_TICKS = 40;

    private ShareHeartsMath() {}

    /** Health the giver pays per gift; 0 at rank 0. */
    public static int cost(int rank) {
        if (rank <= 0) return 0;
        return rank <= 6 ? 2 : 1;
    }

    /** The certain part of the gift; 0 at rank 0. */
    public static int baseGift(int rank) {
        if (rank <= 0) return 0;
        return rank <= 6 ? 1 + (rank - 1) / 2 : 2 + (rank - 7) / 2;
    }

    /** True on points that roll for an extra half heart (every even point). */
    public static boolean hasExtraChance(int rank) {
        return rank > 0 && rank % 2 == 0;
    }

    /** One gift's size: the base, plus the extra half heart when it rolls. */
    public static int rollGift(int rank, double roll) {
        return baseGift(rank) + (hasExtraChance(rank) && roll < EXTRA_CHANCE ? 1 : 0);
    }

    /**
     * Health actually moved: the gift capped at what the target is missing; 0 when the giver can't
     * pay without going under {@link #GIVER_FLOOR}.
     */
    public static float given(float gift, float targetMissing, float giverHealth, int cost) {
        if (gift <= 0 || targetMissing <= 0 || cost <= 0) return 0;
        if (giverHealth - cost < GIVER_FLOOR) return 0;
        return Math.min(gift, targetMissing);
    }
}
