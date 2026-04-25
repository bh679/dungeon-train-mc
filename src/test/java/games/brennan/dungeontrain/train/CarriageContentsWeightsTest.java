package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link CarriageContentsWeights} value type — clamp,
 * default lookup, defensive copy. Mirrors {@link CarriageWeightsTest}; the
 * disk-loader path is covered by the in-game Gate 2 verification because
 * touching FMLPaths from a plain JUnit test would require a Forge bootstrap
 * we deliberately avoid.
 */
final class CarriageContentsWeightsTest {

    @Test
    @DisplayName("EMPTY.weightFor returns DEFAULT for every id")
    void empty_returnsDefault() {
        assertEquals(CarriageContentsWeights.DEFAULT, CarriageContentsWeights.EMPTY.weightFor("default"));
        assertEquals(CarriageContentsWeights.DEFAULT, CarriageContentsWeights.EMPTY.weightFor("anything"));
        assertEquals(CarriageContentsWeights.DEFAULT, CarriageContentsWeights.EMPTY.weightFor(""));
    }

    @Test
    @DisplayName("weightFor returns stored value for known id")
    void weightFor_returnsStoredValue() {
        CarriageContentsWeights w = new CarriageContentsWeights(Map.of("default", 7));
        assertEquals(7, w.weightFor("default"));
    }

    @Test
    @DisplayName("weightFor returns DEFAULT for an unknown id")
    void weightFor_returnsDefaultForUnknown() {
        CarriageContentsWeights w = new CarriageContentsWeights(Map.of("default", 7));
        assertEquals(CarriageContentsWeights.DEFAULT, w.weightFor("furnished"));
    }

    @Test
    @DisplayName("weightFor clamps negative values to MIN")
    void weightFor_clampsBelowMin() {
        CarriageContentsWeights w = new CarriageContentsWeights(Map.of("default", -5));
        assertEquals(CarriageContentsWeights.MIN, w.weightFor("default"));
    }

    @Test
    @DisplayName("weightFor clamps above-max values to MAX")
    void weightFor_clampsAboveMax() {
        CarriageContentsWeights w = new CarriageContentsWeights(Map.of("default", CarriageContentsWeights.MAX + 50));
        assertEquals(CarriageContentsWeights.MAX, w.weightFor("default"));
    }

    @Test
    @DisplayName("weightFor zero is preserved (used to exclude a contents)")
    void weightFor_zeroPreserved() {
        CarriageContentsWeights w = new CarriageContentsWeights(Map.of("default", 0));
        assertEquals(0, w.weightFor("default"));
    }

    @Test
    @DisplayName("clamp bounds values to [MIN, MAX]")
    void clamp_respectsBounds() {
        assertEquals(CarriageContentsWeights.MIN, CarriageContentsWeights.clamp(-100));
        assertEquals(CarriageContentsWeights.MIN, CarriageContentsWeights.clamp(CarriageContentsWeights.MIN));
        assertEquals(CarriageContentsWeights.MAX, CarriageContentsWeights.clamp(CarriageContentsWeights.MAX));
        assertEquals(CarriageContentsWeights.MAX, CarriageContentsWeights.clamp(CarriageContentsWeights.MAX + 1));
        assertEquals(42, CarriageContentsWeights.clamp(42));
    }

    @Test
    @DisplayName("Constructor defensively copies the input map")
    void constructor_defensivelyCopiesInputMap() {
        Map<String, Integer> mutable = new HashMap<>();
        mutable.put("default", 5);
        CarriageContentsWeights w = new CarriageContentsWeights(mutable);
        mutable.put("default", 99);
        mutable.put("furnished", 0);
        assertEquals(5, w.weightFor("default"), "record should snapshot the input map");
        assertEquals(CarriageContentsWeights.DEFAULT, w.weightFor("furnished"), "added-after keys should not leak in");
    }

    @Test
    @DisplayName("Returned map from byId is unmodifiable")
    void byId_returnsUnmodifiable() {
        CarriageContentsWeights w = new CarriageContentsWeights(Map.of("default", 5));
        assertThrows(UnsupportedOperationException.class,
                () -> w.byId().put("extra", 1));
    }

    @Test
    @DisplayName("EMPTY is shared — identity holds across accesses")
    void empty_isShared() {
        assertTrue(CarriageContentsWeights.EMPTY == CarriageContentsWeights.EMPTY);
        assertTrue(CarriageContentsWeights.EMPTY.byId().isEmpty());
    }

    @Test
    @DisplayName("MIN is 0, DEFAULT is 1, MAX is 100 (contract for weights.json authors)")
    void constants_matchDocumentedContract() {
        assertEquals(0, CarriageContentsWeights.MIN);
        assertEquals(1, CarriageContentsWeights.DEFAULT);
        assertEquals(100, CarriageContentsWeights.MAX);
    }
}
