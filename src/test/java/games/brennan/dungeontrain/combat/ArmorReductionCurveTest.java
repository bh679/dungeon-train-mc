package games.brennan.dungeontrain.combat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ArmorReductionCurve}: vanilla below 80%, the anchors, toughness past 80%, and the 90% promise. */
final class ArmorReductionCurveTest {

    private static final float EPS = 1e-4f;
    private static final float[] HITS = {0.5f, 5.0f, 20.0f, 60.0f, 200.0f};

    private static float vanilla(float armor, float toughness, float damage) {
        float raw = armor - damage / (2.0f + toughness / 4.0f);
        return Math.min(Math.max(raw, armor * 0.2f), 20.0f) / 25.0f;
    }

    @Test
    @DisplayName("matches vanilla exactly wherever vanilla blocks less than 80%")
    void vanillaBelowCap() {
        for (float damage : HITS) {
            for (float t = 0.0f; t <= 40.0f; t += 4.0f) {
                for (float a = 0.0f; a <= 120.0f; a += 0.5f) {
                    float v = vanilla(a, t, damage);
                    if (v < 0.8f - EPS) {
                        assertEquals(v, ArmorReductionCurve.reduction(a, t, damage), EPS,
                            "armor=" + a + " toughness=" + t + " damage=" + damage);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("hits the anchors with no toughness: 20 = 80%, 120 = 83%, 320 = 84%")
    void anchors() {
        assertEquals(0.80f, ArmorReductionCurve.curve(20.0f), EPS);
        assertEquals(0.83f, ArmorReductionCurve.curve(120.0f), EPS);
        assertEquals(0.84f, ArmorReductionCurve.curve(320.0f), EPS);
        // A tiny hit barely pierces, so the armor value is the curve input.
        assertEquals(0.83f, ArmorReductionCurve.reduction(120.0f, 0.0f, 0.0f), EPS);
    }

    @Test
    @DisplayName("toughness keeps adding past 80%, one armor point per spare point")
    void toughnessPastCap() {
        // Armor 23, 20-damage hit: vanilla needs toughness 18.67 to reach the cap.
        float needed = ArmorReductionCurve.toughnessToReachCap(23.0f, 20.0f);
        assertEquals(18.6667f, needed, 1e-3f);
        float atNeeded = ArmorReductionCurve.reduction(23.0f, needed, 20.0f);
        assertEquals(0.80f, atNeeded, 1e-3f);
        assertEquals(ArmorReductionCurve.curve(20.0f + 128.0f - needed),
            ArmorReductionCurve.reduction(23.0f, 128.0f, 20.0f), 1e-3f);
        assertTrue(ArmorReductionCurve.reduction(23.0f, 1024.0f, 20.0f) > 0.86f);
        // At or below 20 armor toughness can never lift you past 80%.
        assertTrue(ArmorReductionCurve.reduction(20.0f, 1024.0f, 5.0f) <= 0.80f + EPS);
    }

    @Test
    @DisplayName("never decreases with more armor or toughness, never reaches 90%")
    void monotonicAndBounded() {
        for (float damage : HITS) {
            float prev = 0.0f;
            for (float a = 0.0f; a <= 5000.0f; a += 0.5f) {
                float r = ArmorReductionCurve.reduction(a, 12.0f, damage);
                assertTrue(r >= prev - 1e-6f, "armor dropped at " + a + " damage=" + damage);
                assertTrue(r < ArmorReductionCurve.MAX_REDUCTION);
                prev = r;
            }
            for (float armor : new float[] {10.0f, 23.0f, 60.0f, 150.0f, 1024.0f}) {
                prev = 0.0f;
                for (float t = 0.0f; t <= 5000.0f; t += 0.5f) {
                    float r = ArmorReductionCurve.reduction(armor, t, damage);
                    assertTrue(r >= prev - 1e-6f, "toughness dropped at " + t + " armor=" + armor);
                    assertTrue(r < ArmorReductionCurve.MAX_REDUCTION);
                    prev = r;
                }
            }
        }
        assertTrue(ArmorReductionCurve.reduction(Float.MAX_VALUE, Float.MAX_VALUE, 1.0f) < ArmorReductionCurve.MAX_REDUCTION);
        assertTrue(ArmorReductionCurve.reduction(Float.POSITIVE_INFINITY, 0.0f, 1.0f) < ArmorReductionCurve.MAX_REDUCTION);
    }

    @Test
    @DisplayName("no jumps as toughness or armor carries a hit past 80%")
    void continuous() {
        for (float damage : HITS) {
            for (float armor : new float[] {21.0f, 23.0f, 40.0f, 99.0f}) {
                float needed = ArmorReductionCurve.toughnessToReachCap(armor, damage);
                if (needed > 0.0f && needed < 1000.0f) {
                    float below = ArmorReductionCurve.reduction(armor, needed - 0.0005f, damage);
                    float above = ArmorReductionCurve.reduction(armor, needed + 0.0005f, damage);
                    assertEquals(below, above, 1e-3f, "armor=" + armor + " damage=" + damage);
                }
            }
        }
        // Armor crossing 100 under a huge hit, where vanilla's armor * 0.2 floor takes over.
        for (float t : new float[] {0.0f, 12.0f, 50.0f, 500.0f}) {
            assertEquals(ArmorReductionCurve.reduction(99.99f, t, 1000.0f),
                ArmorReductionCurve.reduction(100.01f, t, 1000.0f), 1e-3f, "toughness=" + t);
        }
        for (float join : new float[] {20.0f, 120.0f, 320.0f}) {
            assertEquals(ArmorReductionCurve.curve(join - 0.01f), ArmorReductionCurve.curve(join + 0.01f), 1e-4f);
        }
    }

    @Test
    @DisplayName("negative or NaN input blocks nothing")
    void badInput() {
        assertEquals(0.0f, ArmorReductionCurve.reduction(-5.0f, 0.0f, 1.0f));
        assertEquals(0.0f, ArmorReductionCurve.reduction(Float.NaN, 0.0f, 1.0f));
    }
}
