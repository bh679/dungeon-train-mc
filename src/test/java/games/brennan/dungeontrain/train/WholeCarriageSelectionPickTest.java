package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure weighted draw {@link WholeCarriageSelection} and {@link WholeGroupSelection} share. */
final class WholeCarriageSelectionPickTest {

    private static final List<String> POOL = List.of("a", "b", "c");

    @Test
    @DisplayName("stable for the same (seed, index)")
    void stable() {
        Map<String, Integer> w = Map.of("a", 1, "b", 1, "c", 1);
        for (int i = -500; i < 500; i++) {
            assertEquals(WholeCarriageSelection.weightedSeededPick(7L, i, POOL, w::get),
                WholeCarriageSelection.weightedSeededPick(7L, i, POOL, w::get));
        }
    }

    @Test
    @DisplayName("a weight of zero is never drawn while something else has weight")
    void zeroWeightExcluded() {
        Map<String, Integer> w = Map.of("a", 0, "b", 5, "c", 5);
        for (int i = 0; i < 2_000; i++) {
            assertFalse("a".equals(WholeCarriageSelection.weightedSeededPick(11L, i, POOL, w::get)));
        }
    }

    @Test
    @DisplayName("the draw follows the weights over a long run")
    void weightsBias() {
        Map<String, Integer> w = Map.of("a", 1, "b", 3, "c", 6);
        Map<String, Integer> counts = new HashMap<>();
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            counts.merge(WholeCarriageSelection.weightedSeededPick(3L, i, POOL, w::get), 1, Integer::sum);
        }
        assertTrue(Math.abs(counts.get("a") / (double) n - 0.1) < 0.02);
        assertTrue(Math.abs(counts.get("b") / (double) n - 0.3) < 0.03);
        assertTrue(Math.abs(counts.get("c") / (double) n - 0.6) < 0.03);
    }

    @Test
    @DisplayName("all-zero weights still return something — a slot is never left unfillable")
    void allZeroStillPicks() {
        Map<String, Integer> w = Map.of("a", 0, "b", 0, "c", 0);
        assertTrue(POOL.contains(WholeCarriageSelection.weightedSeededPick(5L, 9, POOL, w::get)));
    }

    @Test
    @DisplayName("the whole variant is recognised by id alone")
    void wholeVariantById() {
        assertTrue(WholeCarriageSelection.isWholeVariant(new CarriageVariant.Custom(WholeCarriageSelection.VARIANT_ID)));
        assertFalse(WholeCarriageSelection.isWholeVariant(new CarriageVariant.Custom("shared")));
        assertFalse(WholeCarriageSelection.isWholeVariant(null));
    }
}
