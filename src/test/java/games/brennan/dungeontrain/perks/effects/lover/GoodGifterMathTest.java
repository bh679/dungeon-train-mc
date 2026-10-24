package games.brennan.dungeontrain.perks.effects.lover;

import games.brennan.dungeontrain.perks.effects.lover.GoodGifterMath.Offering;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GoodGifterMathTest {

    private static final double EPS = 1e-9;

    @Test
    void unlockRanksFollowTheLadder() {
        assertEquals(1, Offering.SIMILAR_FOOD.unlockRank);
        assertEquals(5, Offering.OTHER_ANIMAL_FOOD.unlockRank);
        assertEquals(10, Offering.PLAYER_FOOD.unlockRank);
        assertEquals(20, Offering.ANY_ITEM.unlockRank);
        assertEquals(30, Offering.NOTHING.unlockRank);
        assertFalse(GoodGifterMath.unlocked(Offering.PLAYER_FOOD, 9));
        assertTrue(GoodGifterMath.unlocked(Offering.PLAYER_FOOD, 10));
    }

    @Test
    void rightFoodIsVanillaAtRankZeroAndGrowsEachRank() {
        assertEquals(1.0, GoodGifterMath.strength(Offering.RIGHT_FOOD, 0), EPS);
        assertEquals(1.1, GoodGifterMath.strength(Offering.RIGHT_FOOD, 1), EPS);
        assertEquals(4.0, GoodGifterMath.strength(Offering.RIGHT_FOOD, 30), EPS);
    }

    @Test
    void eachUnlockStartsAtHalfTheRightFoodsStartThenGrows() {
        for (Offering o : Offering.values()) {
            if (o == Offering.RIGHT_FOOD) continue;
            assertEquals(0.0, GoodGifterMath.strength(o, o.unlockRank - 1), EPS);
            assertEquals(0.5, GoodGifterMath.strength(o, o.unlockRank), EPS);
            assertEquals(0.6, GoodGifterMath.strength(o, o.unlockRank + 1), EPS);
        }
    }

    @Test
    void tameChanceScalesVanillaOddsAndCapsAtCertain() {
        double bone = GoodGifterMath.DEFAULT_TAME_CHANCE;
        assertEquals(bone / 2, GoodGifterMath.tameChance(Offering.SIMILAR_FOOD, 1, bone), EPS);
        assertEquals(0.0, GoodGifterMath.tameChance(Offering.NOTHING, 29, bone), EPS);
        assertEquals(1.0, GoodGifterMath.tameChance(Offering.RIGHT_FOOD, 1000, bone), EPS);
    }

    @Test
    void emptyHandTamesButNeverFeeds() {
        assertFalse(GoodGifterMath.feeds(Offering.NOTHING, 100));
        assertTrue(GoodGifterMath.feeds(Offering.ANY_ITEM, 20));
    }

    @Test
    void horseTemperIsAtLeastOneOnceUnlocked() {
        assertEquals(0, GoodGifterMath.horseTemper(Offering.ANY_ITEM, 19));
        assertEquals(2, GoodGifterMath.horseTemper(Offering.ANY_ITEM, 20));
        assertEquals(3, GoodGifterMath.horseTemper(Offering.RIGHT_FOOD, 0));
    }

    @Test
    void playerMobBonusScalesWithRankAndClampsNegative() {
        assertEquals(0.0f, GoodGifterMath.playerMobFeelingBonus(-1));
        assertEquals(GoodGifterMath.PLAYER_MOB_FEELING_PER_RANK * 10, GoodGifterMath.playerMobFeelingBonus(10), 1e-6);
    }
}
