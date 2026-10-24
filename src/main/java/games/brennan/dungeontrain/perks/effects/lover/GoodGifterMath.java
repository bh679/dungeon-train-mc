package games.brennan.dungeontrain.perks.effects.lover;

/**
 * Pure rules for Good Gifter: how picky animals are about what the player offers, by rank.
 *
 * <p>Rank 1: anything edible by a player counts as food for taming and breeding. Rank 2: any
 * held item counts. At {@link #MAX_RANK} an empty hand tames too (breeding still needs an item
 * to consume). Every number here is a balancing knob.</p>
 */
public final class GoodGifterMath {

    /** Rank at which taming needs no food at all. Unverified against the live tree. */
    public static final int MAX_RANK = 3;
    /** Rank from which player-edible items count as animal food. */
    public static final int ANY_FOOD_RANK = 1;
    /** Rank from which any held item counts as animal food. */
    public static final int ANY_ITEM_RANK = 2;
    /** Odds of a lenient offering taming a tamable animal: one in this. Vanilla bone is 1 in 3. */
    public static final int TAME_ONE_IN = 3;
    /** Temper a lenient offering gives an untamed horse. Vanilla wheat gives 3, apple 3, bread 3. */
    public static final int HORSE_TEMPER = 3;
    /** Extra feeling a PlayerMob gains per rank when the player gifts it (feeling runs 0 to 10). */
    public static final float PLAYER_MOB_FEELING_PER_RANK = 0.5f;

    private GoodGifterMath() {}

    /** Whether an offering the animal would normally refuse counts as food at this rank. */
    public static boolean acceptsAsFood(int rank, boolean playerEdible, boolean emptyHand) {
        if (emptyHand || rank < ANY_FOOD_RANK) return false;
        if (rank >= ANY_ITEM_RANK) return true;
        return playerEdible;
    }

    /** Whether an empty hand tames at this rank. */
    public static boolean tamesWithNothing(int rank) {
        return rank >= MAX_RANK;
    }

    /** Whether an offering (possibly nothing) can be used to tame at this rank. */
    public static boolean acceptsForTaming(int rank, boolean playerEdible, boolean emptyHand) {
        return emptyHand ? tamesWithNothing(rank) : acceptsAsFood(rank, playerEdible, false);
    }

    /** Feeling bonus a PlayerMob gets on top of its own gift credit. */
    public static float playerMobFeelingBonus(int rank) {
        return PLAYER_MOB_FEELING_PER_RANK * Math.max(0, rank);
    }
}
