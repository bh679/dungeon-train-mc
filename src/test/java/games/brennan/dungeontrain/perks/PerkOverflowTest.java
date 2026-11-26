package games.brennan.dungeontrain.perks;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerkOverflowTest {

    @Test
    @DisplayName("full hundreds are certain, the rest is a chance of one more")
    void splitsAndRolls() {
        assertEquals(0, PerkOverflow.guaranteed(95));
        assertEquals(95, PerkOverflow.remainder(95));
        assertEquals(1, PerkOverflow.guaranteed(100));
        assertEquals(0, PerkOverflow.remainder(100));
        assertEquals(2, PerkOverflow.guaranteed(215));
        assertEquals(15, PerkOverflow.remainder(215));
        assertEquals(0, PerkOverflow.guaranteed(-5));
        RandomSource random = RandomSource.create(7);
        for (int i = 0; i < 200; i++) {
            assertEquals(0, PerkOverflow.rollSteps(0, random));
            assertEquals(1, PerkOverflow.rollSteps(100, random));
            int s = PerkOverflow.rollSteps(150, random);
            assertEquals(true, s == 1 || s == 2);
        }
    }

    @Test
    @DisplayName("the chance part lands near its percentage")
    void chanceIsFair() {
        RandomSource random = RandomSource.create(11);
        int n = 20000, total = 0;
        for (int i = 0; i < n; i++) total += PerkOverflow.rollSteps(30, random);
        assertEquals(0.30, total / (double) n, 0.02);
    }
}
