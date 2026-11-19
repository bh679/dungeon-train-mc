package games.brennan.dungeontrain.compat;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriedGearRollTest {

    private static final int ROLLS = 400_000;

    @Test
    void sevenInEightWearNothingAndEachExtraItemHalvesTheOdds() {
        RandomSource rng = RandomSource.create(1234L);
        int[] counts = new int[CarriedGearRoll.MAX_ITEMS + 1];
        for (int i = 0; i < ROLLS; i++) {
            int items = CarriedGearRoll.roll(rng);
            assertTrue(items >= 0 && items <= CarriedGearRoll.MAX_ITEMS, "out of range: " + items);
            counts[items]++;
        }
        assertEquals(7.0 / 8.0, counts[0] / (double) ROLLS, 0.005, "share wearing nothing");

        int wearing = ROLLS - counts[0];
        assertEquals(0.5, counts[1] / (double) wearing, 0.02, "1 item");
        assertEquals(0.25, counts[2] / (double) wearing, 0.02, "2 items");
        assertEquals(0.125, counts[3] / (double) wearing, 0.015, "3 items");
    }

    @Test
    void sameSeedSameRolls() {
        RandomSource a = RandomSource.create(99L);
        RandomSource b = RandomSource.create(99L);
        for (int i = 0; i < 1000; i++) {
            assertEquals(CarriedGearRoll.roll(a), CarriedGearRoll.roll(b));
        }
    }
}
