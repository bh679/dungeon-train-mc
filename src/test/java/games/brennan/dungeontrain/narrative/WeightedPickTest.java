package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down {@link WeightedPick} — the seed→target step every narrative pool shares. Two things
 * matter: the same seed always yields the same target (lecterns and chests must be stable across
 * re-clicks), and a 0.1-weight entry really is picked about a tenth as often as a 1.0 one.
 */
final class WeightedPickTest {

    @Test
    @DisplayName("same seed, same target")
    void isDeterministic() {
        assertEquals(WeightedPick.target(123456789L, 24.3), WeightedPick.target(123456789L, 24.3));
    }

    @Test
    @DisplayName("target always lands inside [0, total)")
    void staysInRange() {
        double total = 4.6;
        for (long seed = -5_000L; seed < 5_000L; seed++) {
            double target = WeightedPick.target(seed, total);
            assertTrue(target >= 0 && target < total, "seed " + seed + " gave " + target);
        }
    }

    @Test
    @DisplayName("a 0.1-weight entry is picked about a tenth as often as a 1.0 one")
    void fractionalWeightsShareProportionally() {
        double[] weights = {1.0, 1.0, 0.1};   // two baseline books, one meta book
        double total = 2.1;
        int[] hits = new int[weights.length];
        int rolls = 100_000;
        for (int i = 0; i < rolls; i++) {
            // Spread the seeds across the mapping's resolution rather than walking 0..n.
            double target = WeightedPick.target(i * 7919L, total);
            for (int w = 0; w < weights.length; w++) {
                target -= weights[w];
                if (target < 0) { hits[w]++; break; }
            }
        }
        double metaShare = hits[2] / (double) rolls;
        double expected = 0.1 / total;
        assertTrue(Math.abs(metaShare - expected) < 0.01,
            "meta share " + metaShare + " should be near " + expected);
        assertTrue(hits[2] > 0, "the meta book must still be reachable, just rare");
    }
}
