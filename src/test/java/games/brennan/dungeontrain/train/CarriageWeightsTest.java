package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link CarriageWeights} value type — clamp, default lookup,
 * defensive copy. The disk-loader path is covered indirectly via
 * {@link CarriageTemplateTest}'s weighted-distribution tests and the in-game
 * Gate 2 verification; testing it directly would require a Forge {@code FMLPaths}
 * bootstrap which these plain JUnit tests deliberately avoid.
 */
final class CarriageWeightsTest {

    @Test
    @DisplayName("EMPTY.weightFor returns DEFAULT for every id")
    void empty_returnsDefault() {
        assertEquals(CarriageWeights.DEFAULT, CarriageWeights.EMPTY.weightFor("standard"));
        assertEquals(CarriageWeights.DEFAULT, CarriageWeights.EMPTY.weightFor("anything"));
        assertEquals(CarriageWeights.DEFAULT, CarriageWeights.EMPTY.weightFor(""));
    }

    @Test
    @DisplayName("weightFor returns stored value for known id")
    void weightFor_returnsStoredValue() {
        CarriageWeights w = new CarriageWeights(Map.of("standard", 7));
        assertEquals(7, w.weightFor("standard"));
    }

    @Test
    @DisplayName("weightFor returns DEFAULT for an unknown id")
    void weightFor_returnsDefaultForUnknown() {
        CarriageWeights w = new CarriageWeights(Map.of("standard", 7));
        assertEquals(CarriageWeights.DEFAULT, w.weightFor("windowed"));
    }

    @Test
    @DisplayName("weightFor clamps negative values to MIN")
    void weightFor_clampsBelowMin() {
        // The record constructor keeps raw values as given; clamping happens
        // on read. A map with -5 stored in it should surface as MIN (0).
        CarriageWeights w = new CarriageWeights(Map.of("standard", -5));
        assertEquals(CarriageWeights.MIN, w.weightFor("standard"));
    }

    @Test
    @DisplayName("weightFor clamps above-max values to MAX")
    void weightFor_clampsAboveMax() {
        CarriageWeights w = new CarriageWeights(Map.of("standard", CarriageWeights.MAX + 50));
        assertEquals(CarriageWeights.MAX, w.weightFor("standard"));
    }

    @Test
    @DisplayName("weightFor zero is preserved (used to exclude a variant)")
    void weightFor_zeroPreserved() {
        CarriageWeights w = new CarriageWeights(Map.of("flatbed", 0));
        assertEquals(0, w.weightFor("flatbed"));
    }

    @Test
    @DisplayName("clamp bounds values to [MIN, MAX]")
    void clamp_respectsBounds() {
        assertEquals(CarriageWeights.MIN, CarriageWeights.clamp(-100));
        assertEquals(CarriageWeights.MIN, CarriageWeights.clamp(CarriageWeights.MIN));
        assertEquals(CarriageWeights.MAX, CarriageWeights.clamp(CarriageWeights.MAX));
        assertEquals(CarriageWeights.MAX, CarriageWeights.clamp(CarriageWeights.MAX + 1));
        assertEquals(42, CarriageWeights.clamp(42));
    }

    @Test
    @DisplayName("Constructor defensively copies the input map")
    void constructor_defensivelyCopiesInputMap() {
        Map<String, Integer> mutable = new HashMap<>();
        mutable.put("standard", 5);
        CarriageWeights w = new CarriageWeights(mutable);
        mutable.put("standard", 99);      // mutate after construction
        mutable.put("windowed", 0);
        assertEquals(5, w.weightFor("standard"), "record should snapshot the input map");
        assertEquals(CarriageWeights.DEFAULT, w.weightFor("windowed"), "added-after keys should not leak in");
    }

    @Test
    @DisplayName("Returned map from byId is unmodifiable")
    void byId_returnsUnmodifiable() {
        CarriageWeights w = new CarriageWeights(Map.of("standard", 5));
        assertThrows(UnsupportedOperationException.class,
                () -> w.byId().put("extra", 1));
    }

    @Test
    @DisplayName("EMPTY is shared — identity holds across accesses")
    void empty_isShared() {
        assertTrue(CarriageWeights.EMPTY == CarriageWeights.EMPTY);
        assertTrue(CarriageWeights.EMPTY.byId().isEmpty());
    }

    @Test
    @DisplayName("MIN is 0, DEFAULT is 1, MAX is 100 (contract for weights.json authors)")
    void constants_matchDocumentedContract() {
        // These constants appear in the user-facing weights.json docs, so pin
        // them here. Changing any of them is a deliberate contract change.
        assertEquals(0, CarriageWeights.MIN);
        assertEquals(1, CarriageWeights.DEFAULT);
        assertEquals(100, CarriageWeights.MAX);
    }
}
