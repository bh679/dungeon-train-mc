package games.brennan.dungeontrain.perks.effects.lover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShareHeartsMathTest {

    /** The confirmed ladder in health points: {rank, cost, base gift, rolls for +1}. */
    private static final int[][] LADDER = {
        {1, 2, 1, 0}, {2, 2, 1, 1}, {3, 2, 2, 0}, {4, 2, 2, 1}, {5, 2, 3, 0}, {6, 2, 3, 1},
        {7, 1, 2, 0}, {8, 1, 2, 1}, {9, 1, 3, 0}, {10, 1, 3, 1}, {11, 1, 4, 0},
    };

    @Test
    void matchesTheConfirmedLadder() {
        for (int[] row : LADDER) {
            int r = row[0];
            assertEquals(row[1], ShareHeartsMath.cost(r), "cost at " + r);
            assertEquals(row[2], ShareHeartsMath.baseGift(r), "gift at " + r);
            assertEquals(row[3] == 1, ShareHeartsMath.hasExtraChance(r), "chance at " + r);
        }
    }

    @Test
    void offAtRankZero() {
        assertEquals(0, ShareHeartsMath.cost(0));
        assertEquals(0, ShareHeartsMath.baseGift(0));
        assertFalse(ShareHeartsMath.hasExtraChance(0));
    }

    @Test
    void noTwoPointsTheSame() {
        for (int r = 1; r < 60; r++) {
            boolean same = ShareHeartsMath.cost(r) == ShareHeartsMath.cost(r + 1)
                && ShareHeartsMath.baseGift(r) == ShareHeartsMath.baseGift(r + 1)
                && ShareHeartsMath.hasExtraChance(r) == ShareHeartsMath.hasExtraChance(r + 1);
            assertFalse(same, "points " + r + " and " + (r + 1));
        }
    }

    @Test
    void rollAddsHalfHeartOnlyOnChancePoints() {
        assertEquals(2, ShareHeartsMath.rollGift(2, 0.1));
        assertEquals(1, ShareHeartsMath.rollGift(2, 0.9));
        assertEquals(2, ShareHeartsMath.rollGift(3, 0.1));
    }

    @Test
    void giftIsCappedAndNeverTakesGiverUnderOneHeart() {
        assertEquals(1.0F, ShareHeartsMath.given(3, 1.0F, 20.0F, 2));
        assertEquals(0.0F, ShareHeartsMath.given(3, 0.0F, 20.0F, 2));
        assertEquals(0.0F, ShareHeartsMath.given(3, 5.0F, 3.0F, 2));
        assertTrue(ShareHeartsMath.given(3, 5.0F, 4.0F, 2) > 0);
    }
}
