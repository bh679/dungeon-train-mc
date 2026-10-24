package games.brennan.dungeontrain.perks.effects.lover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GoodGifterMathTest {

    @Test
    void rankZeroChangesNothing() {
        assertFalse(GoodGifterMath.acceptsAsFood(0, true, false));
        assertFalse(GoodGifterMath.acceptsAsFood(0, false, false));
        assertFalse(GoodGifterMath.acceptsForTaming(0, false, true));
    }

    @Test
    void rankOneTakesPlayerFoodOnly() {
        assertTrue(GoodGifterMath.acceptsAsFood(1, true, false));
        assertFalse(GoodGifterMath.acceptsAsFood(1, false, false));
        assertFalse(GoodGifterMath.acceptsForTaming(1, false, true));
    }

    @Test
    void rankTwoTakesAnyItemButNotNothing() {
        assertTrue(GoodGifterMath.acceptsAsFood(2, false, false));
        assertFalse(GoodGifterMath.acceptsAsFood(2, false, true));
        assertFalse(GoodGifterMath.tamesWithNothing(2));
    }

    @Test
    void maxRankTamesWithEmptyHandButBreedingStillNeedsAnItem() {
        assertTrue(GoodGifterMath.tamesWithNothing(GoodGifterMath.MAX_RANK));
        assertTrue(GoodGifterMath.acceptsForTaming(GoodGifterMath.MAX_RANK, false, true));
        assertFalse(GoodGifterMath.acceptsAsFood(GoodGifterMath.MAX_RANK, false, true));
    }

    @Test
    void playerMobBonusScalesWithRankAndClampsNegative() {
        assertEquals(0.0f, GoodGifterMath.playerMobFeelingBonus(0));
        assertEquals(0.0f, GoodGifterMath.playerMobFeelingBonus(-1));
        assertEquals(GoodGifterMath.PLAYER_MOB_FEELING_PER_RANK * 2, GoodGifterMath.playerMobFeelingBonus(2));
    }
}
