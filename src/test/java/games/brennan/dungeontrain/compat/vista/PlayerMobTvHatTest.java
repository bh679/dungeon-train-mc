package games.brennan.dungeontrain.compat.vista;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMobTvHatTest {

    @Test
    void chanceIsOneInFifteen() {
        assertEquals(15, PlayerMobTvHat.ONE_IN);
    }

    @Test
    void rollLandsAboutOneInFifteen() {
        RandomSource rng = RandomSource.create(42L);
        int trials = 150_000;
        int hits = 0;
        for (int i = 0; i < trials; i++) {
            if (PlayerMobTvHat.rolls(rng)) hits++;
        }
        double rate = (double) hits / trials;
        assertTrue(Math.abs(rate - 1.0 / 15) < 0.003, "rate was " + rate);
    }
}
