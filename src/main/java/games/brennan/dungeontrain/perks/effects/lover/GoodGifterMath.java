package games.brennan.dungeontrain.perks.effects.lover;

/**
 * Pure rules for Good Gifter: which offerings an animal accepts at a rank, and how well they work.
 *
 * <p>Each {@link Offering} unlocks at its own rank. The animal's right food works from rank 0 at
 * full strength; every other offering starts, on its unlock rank, at half the strength the right
 * food started at. Every rank after that adds {@link #GROWTH_PER_RANK} of the starting strength,
 * for every offering alike and with no cap on rank. Strength multiplies the animal's own vanilla
 * tame chance (capped at certain) and the temper a horse gains. Every number here is a balancing
 * knob.</p>
 */
public final class GoodGifterMath {

    /** What the player holds out, from best to worst. Checked in this order; the first match wins. */
    public enum Offering {
        /** The animal's own food (bone for a wolf, fish for a cat). */
        RIGHT_FOOD(0),
        /** Food of the same kind the animal eats: meat or fish for meat eaters, plants for plant eaters. */
        SIMILAR_FOOD(1),
        /** Something some other animal eats. */
        OTHER_ANIMAL_FOOD(5),
        /** Anything a player can eat. */
        PLAYER_FOOD(10),
        /** Any item at all. */
        ANY_ITEM(20),
        /** An empty hand. Tames only; breeding and growing babies still need an item. */
        NOTHING(30);

        public final int unlockRank;

        Offering(int unlockRank) {
            this.unlockRank = unlockRank;
        }
    }

    /** Strength an offering other than the right food starts at, as a share of the right food's start. */
    public static final double UNLOCK_STRENGTH = 0.5;
    /** Strength each rank past an offering's unlock adds, as a share of the right food's start. */
    public static final double GROWTH_PER_RANK = 0.25;
    /** Vanilla tame odds for a bone, fish or similar: 1 in 3. Parrots use {@link #PARROT_TAME_CHANCE}. */
    public static final double DEFAULT_TAME_CHANCE = 1.0 / 3.0;
    /** Vanilla parrot odds with seeds: 1 in 10. */
    public static final double PARROT_TAME_CHANCE = 1.0 / 10.0;
    /** Temper the right food gives an untamed horse (wheat, apple, bread give 3). */
    public static final int HORSE_TEMPER = 3;
    /** Extra feeling a PlayerMob gains per rank when the player gifts it (feeling runs 0 to 10). */
    public static final float PLAYER_MOB_FEELING_PER_RANK = 0.1f;

    private GoodGifterMath() {}

    /** Whether {@code offering} is accepted at {@code rank}. The right food is always accepted. */
    public static boolean unlocked(Offering offering, int rank) {
        return rank >= offering.unlockRank;
    }

    /**
     * Strength of {@code offering} at {@code rank}, where 1.0 is the right food with no perk.
     * 0 when the offering is not unlocked yet.
     */
    public static double strength(Offering offering, int rank) {
        if (!unlocked(offering, rank)) return 0.0;
        double start = offering == Offering.RIGHT_FOOD ? 1.0 : UNLOCK_STRENGTH;
        return start + GROWTH_PER_RANK * (rank - offering.unlockRank);
    }

    /** Chance one offering tames an animal whose vanilla odds with its right food are {@code baseChance}. */
    public static double tameChance(Offering offering, int rank, double baseChance) {
        return Math.min(1.0, baseChance * strength(offering, rank));
    }

    /** Temper one offering gives an untamed horse; at least 1 once unlocked. */
    public static int horseTemper(Offering offering, int rank) {
        if (!unlocked(offering, rank)) return 0;
        return Math.max(1, (int) Math.round(HORSE_TEMPER * strength(offering, rank)));
    }

    /** Whether the offering can breed or grow a baby: anything unlocked except an empty hand. */
    public static boolean feeds(Offering offering, int rank) {
        return offering != Offering.NOTHING && unlocked(offering, rank);
    }

    /** Feeling bonus a PlayerMob gets on top of its own gift credit. */
    public static float playerMobFeelingBonus(int rank) {
        return PLAYER_MOB_FEELING_PER_RANK * Math.max(0, rank);
    }
}
