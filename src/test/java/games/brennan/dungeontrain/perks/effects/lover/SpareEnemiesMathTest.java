package games.brennan.dungeontrain.perks.effects.lover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SpareEnemiesMathTest {

    @Test
    void fleeLineLadder() {
        assertEquals(0.0, SpareEnemiesMath.fleePercent(0), 1e-9);
        assertEquals(20.0, SpareEnemiesMath.fleePercent(1), 1e-9);
        assertEquals(35.0, SpareEnemiesMath.fleePercent(2), 1e-9);
        assertEquals(40.0, SpareEnemiesMath.fleePercent(4), 1e-9);
        assertEquals(45.0, SpareEnemiesMath.fleePercent(8), 1e-9);
        assertEquals(50.0, SpareEnemiesMath.fleePercent(16), 1e-9);
        assertEquals(60.0, SpareEnemiesMath.fleePercent(64), 1e-9);
        assertEquals(60.1, SpareEnemiesMath.fleePercent(65), 1e-9);
    }

    @Test
    void easesAndEveryPointShowsANewValue() {
        double lastStep = Double.MAX_VALUE;
        for (int r = 2; r < 400; r++) {
            double step = SpareEnemiesMath.fleePercent(r + 1) - SpareEnemiesMath.fleePercent(r);
            assertTrue(step > 0, "grows at " + r);
            assertTrue(step <= lastStep + 1e-12, "eases at " + r);
            assertTrue(step >= 0.1 - 1e-9, "visible step at " + r);
            lastStep = step;
            // the chip shows one decimal: neighbouring points must read differently
            assertFalse(String.format("%.1f", SpareEnemiesMath.fleePercent(r))
                .equals(String.format("%.1f", SpareEnemiesMath.fleePercent(r + 1))), "chip repeats at " + r);
        }
    }

    @Test
    void sparesOnlyBelowTheLine() {
        assertTrue(SpareEnemiesMath.spares(3.0F, 20.0F, 1));
        assertFalse(SpareEnemiesMath.spares(5.0F, 20.0F, 1));
        assertFalse(SpareEnemiesMath.spares(0.0F, 20.0F, 5));
        assertFalse(SpareEnemiesMath.spares(1.0F, 20.0F, 0));
    }
}
