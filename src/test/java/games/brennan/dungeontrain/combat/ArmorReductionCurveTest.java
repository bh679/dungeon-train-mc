package games.brennan.dungeontrain.combat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ArmorReductionCurve}: vanilla parity up to 100, Brennan's anchors, and the 90% promise. */
final class ArmorReductionCurveTest {

    private static final float EPS = 1e-4f;

    private static float vanilla(float raw) {
        return Math.min(Math.max(raw, 0.0f), 20.0f) / 25.0f;
    }

    @Test
    @DisplayName("matches vanilla exactly up to 100 armor points")
    void vanillaParity() {
        for (float raw = 0.0f; raw <= 100.0f; raw += 0.5f) {
            assertEquals(vanilla(raw), ArmorReductionCurve.reduction(raw), EPS, "raw=" + raw);
        }
    }

    @Test
    @DisplayName("hits the requested anchors: 100 = 80%, 200 = 83%, 400 = 84%")
    void anchors() {
        assertEquals(0.80f, ArmorReductionCurve.reduction(100.0f), EPS);
        assertEquals(0.83f, ArmorReductionCurve.reduction(200.0f), EPS);
        assertEquals(0.84f, ArmorReductionCurve.reduction(400.0f), EPS);
    }

    @Test
    @DisplayName("keeps climbing past 400 (AIS's 1024 ceiling lands near 86%)")
    void tail() {
        assertEquals(0.8486f, ArmorReductionCurve.reduction(600.0f), 1e-3f);
        assertEquals(0.8605f, ArmorReductionCurve.reduction(1024.0f), 1e-3f);
    }

    @Test
    @DisplayName("never decreases and never reaches 90%")
    void monotonicAndBounded() {
        float previous = 0.0f;
        for (float raw = 0.0f; raw <= 100_000.0f; raw += 0.25f) {
            float r = ArmorReductionCurve.reduction(raw);
            assertTrue(r >= previous, "dropped at raw=" + raw);
            assertTrue(r < ArmorReductionCurve.MAX_REDUCTION, "reached 90% at raw=" + raw);
            previous = r;
        }
        assertTrue(ArmorReductionCurve.reduction(Float.MAX_VALUE) < ArmorReductionCurve.MAX_REDUCTION);
        assertTrue(ArmorReductionCurve.reduction(Float.POSITIVE_INFINITY) < ArmorReductionCurve.MAX_REDUCTION);
    }

    @Test
    @DisplayName("no jumps at the joins")
    void continuous() {
        for (float join : new float[] {20.0f, 100.0f, 200.0f, 400.0f}) {
            float below = ArmorReductionCurve.reduction(Math.nextDown(join));
            float above = ArmorReductionCurve.reduction(Math.nextUp(join));
            assertEquals(below, above, 1e-4f, "join=" + join);
        }
    }

    @Test
    @DisplayName("negative or NaN input blocks nothing")
    void badInput() {
        assertEquals(0.0f, ArmorReductionCurve.reduction(-5.0f));
        assertEquals(0.0f, ArmorReductionCurve.reduction(Float.NaN));
    }
}
