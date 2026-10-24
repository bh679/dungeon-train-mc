package games.brennan.dungeontrain.perks.effects.hoarder;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the Hoarder Luck rules: rank in 20 containers roll lucky, and a lucky roll lifts luck to Luck I. */
final class HoarderLuckTest {

    private static final int SAMPLES = 20_000;
    private static final double TOLERANCE = 0.01;

    private static double hitRate(int rank) {
        RandomSource random = RandomSource.create(42L);
        int hits = 0;
        for (int i = 0; i < SAMPLES; i++) if (HoarderLuck.rollsLucky(rank, random)) hits++;
        return hits / (double) SAMPLES;
    }

    @Test
    @DisplayName("rank 0 (or negative) never rolls lucky")
    void rankZero_never() {
        assertEquals(0.0, hitRate(0));
        assertEquals(0.0, hitRate(-3));
    }

    @Test
    @DisplayName("rank 1 is 1 in 20, rank 2 is 2 in 20, rank 10 is half")
    void rankScalesByTwentieths() {
        assertEquals(0.05, hitRate(1), TOLERANCE);
        assertEquals(0.10, hitRate(2), TOLERANCE);
        assertEquals(0.50, hitRate(10), TOLERANCE);
    }

    @Test
    @DisplayName("rank 20 and above always rolls lucky")
    void rankTwenty_always() {
        assertEquals(1.0, hitRate(20));
        assertEquals(1.0, hitRate(99));
    }

    @Test
    @DisplayName("a lucky roll lifts no luck to Luck I and never lowers a potion's higher luck")
    void effectiveLuck_liftsToLuckOne() {
        assertEquals(1.0f, HoarderLuck.effectiveLuck(0f, true));
        assertEquals(1.0f, HoarderLuck.effectiveLuck(-1f, true));
        assertEquals(2.0f, HoarderLuck.effectiveLuck(2f, true));
    }

    @Test
    @DisplayName("an unlucky roll leaves the player's own luck alone, Bad Luck included")
    void effectiveLuck_unluckyUnchanged() {
        assertEquals(0f, HoarderLuck.effectiveLuck(0f, false));
        assertEquals(-1f, HoarderLuck.effectiveLuck(-1f, false));
        assertEquals(1f, HoarderLuck.effectiveLuck(1f, false));
        assertFalse(HoarderLuck.rollsLucky(0, RandomSource.create(1L)));
        assertTrue(HoarderLuck.rollsLucky(20, RandomSource.create(1L)));
    }
}
