package games.brennan.dungeontrain.perks.effects.lover;

import games.brennan.dungeontrain.perks.aura.AuraLadder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class LoverAuraTest {

    private static int[] r(int rank) {
        return LoverAura.shape(rank).ranges();
    }

    @Test
    void confirmedLadderRows() {
        assertArrayEquals(new int[] {}, r(0));
        assertArrayEquals(new int[] {2}, r(1));
        assertArrayEquals(new int[] {5}, r(4));
        assertArrayEquals(new int[] {5, 2}, r(5));
        assertArrayEquals(new int[] {5, 4}, r(7));
        assertArrayEquals(new int[] {6, 5, 2}, r(8));
        assertArrayEquals(new int[] {6, 5, 4}, r(10));
        assertArrayEquals(new int[] {7, 6, 5, 2}, r(11));
        assertArrayEquals(new int[] {8, 7, 6, 5, 2}, r(14));
        assertEquals(10, r(29).length);
        assertEquals(2, r(29)[9]);
        assertEquals(5, r(32)[9]);
        assertEquals(14, r(32)[0]);
    }

    @Test
    void pastThirtyTwoAlternatesPlusOneAndLevelRounds() {
        AuraLadder.Shape s32 = LoverAura.shape(32), s33 = LoverAura.shape(33), s34 = LoverAura.shape(34);
        assertEquals(0, s32.levelUps());
        assertEquals(s32.range(0) + 1, s33.range(0));
        assertEquals(0, s33.levelUps());
        assertEquals(s33.range(0), s34.range(0));
        assertEquals(1, s34.levelUps());
    }

    @Test
    void noTwoPointsTheSame() {
        for (int rank = 0; rank < 80; rank++) {
            AuraLadder.Shape a = LoverAura.shape(rank), b = LoverAura.shape(rank + 1);
            boolean same = a.levelUps() == b.levelUps() && java.util.Arrays.equals(a.ranges(), b.ranges());
            assertFalse(same, "points " + rank + " and " + (rank + 1));
        }
    }

    @Test
    void firstDrawIsAlwaysOneOfTheThree() {
        assertEquals(3, LoverAura.FIRST.size());
        for (var e : LoverAura.FIRST) assertFalse(!LoverAura.POOL.contains(e));
    }
}
