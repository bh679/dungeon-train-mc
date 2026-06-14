package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link VariantDifficulty}'s canonical-constructor
 * invariants and the {@link VariantDifficulty#eligible(int)} band check that
 * gates spawn-egg variants by difficulty tier. No Forge / Minecraft bootstrap
 * required — these run in plain JUnit alongside {@link VariantRotationTest}.
 * The full {@code resolve} re-roll path (drop out-of-band eggs, re-pick over a
 * {@code List<VariantState>}) is covered by the in-game integration harness on
 * Project #16, matching {@link CarriageVariantBlocksWeightedTest}.
 */
final class VariantDifficultyTest {

    @Test
    @DisplayName("NONE is (0, ALL) and isDefault=true")
    void none_isDefault() {
        assertEquals(0, VariantDifficulty.NONE.min());
        assertEquals(VariantDifficulty.ALL, VariantDifficulty.NONE.max());
        assertTrue(VariantDifficulty.NONE.isDefault());
    }

    @Test
    @DisplayName("default band is eligible at every tier (backward compatibility)")
    void default_eligibleEverywhere() {
        for (int tier : new int[] {0, 1, 5, 50, 100, 9999}) {
            assertTrue(VariantDifficulty.NONE.eligible(tier), "tier " + tier);
        }
    }

    @Test
    @DisplayName("min clamps to [0, MAX_TIER]")
    void min_clamps() {
        assertEquals(0, new VariantDifficulty(-7, VariantDifficulty.ALL).min());
        assertEquals(VariantDifficulty.MAX_TIER,
            new VariantDifficulty(VariantDifficulty.MAX_TIER + 50, VariantDifficulty.ALL).min());
    }

    @Test
    @DisplayName("max clamps below ALL to ALL and above MAX_TIER to MAX_TIER")
    void max_clamps() {
        assertEquals(VariantDifficulty.ALL, new VariantDifficulty(0, -5).max());
        assertEquals(VariantDifficulty.MAX_TIER, new VariantDifficulty(0, VariantDifficulty.MAX_TIER + 1).max());
    }

    @Test
    @DisplayName("min greater than a finite max is pulled down to max")
    void min_pulledDownToMax() {
        VariantDifficulty d = new VariantDifficulty(8, 3);
        assertEquals(3, d.min());
        assertEquals(3, d.max());
    }

    @Test
    @DisplayName("min may exceed an ALL (unbounded) max without being clamped")
    void min_keptWhenMaxAll() {
        VariantDifficulty d = new VariantDifficulty(9, VariantDifficulty.ALL);
        assertEquals(9, d.min());
        assertEquals(VariantDifficulty.ALL, d.max());
    }

    @Test
    @DisplayName("eligible: finite band [2,5] includes its endpoints and excludes outside")
    void eligible_finiteBand() {
        VariantDifficulty d = new VariantDifficulty(2, 5);
        assertFalse(d.eligible(1));
        assertTrue(d.eligible(2));
        assertTrue(d.eligible(4));
        assertTrue(d.eligible(5));
        assertFalse(d.eligible(6));
    }

    @Test
    @DisplayName("eligible: unbounded max [3, ALL] includes everything from min up")
    void eligible_unboundedMax() {
        VariantDifficulty d = new VariantDifficulty(3, VariantDifficulty.ALL);
        assertFalse(d.eligible(2));
        assertTrue(d.eligible(3));
        assertTrue(d.eligible(100));
    }

    @Test
    @DisplayName("eligible: single-tier band [0,0] only matches tier 0")
    void eligible_singleTier() {
        VariantDifficulty d = new VariantDifficulty(0, 0);
        assertTrue(d.eligible(0));
        assertFalse(d.eligible(1));
    }

    @Test
    @DisplayName("isDefault is false for any non-default band")
    void isDefault_falseForNonDefault() {
        assertFalse(new VariantDifficulty(0, 5).isDefault());
        assertFalse(new VariantDifficulty(2, VariantDifficulty.ALL).isDefault());
        assertFalse(new VariantDifficulty(1, 1).isDefault());
    }

    @Test
    @DisplayName("withMin / withMax return re-clamped copies")
    void withers_reclamp() {
        VariantDifficulty base = new VariantDifficulty(2, 5);
        assertEquals(4, base.withMin(4).min());
        // withMin past the finite ceiling is pulled back down to max.
        assertEquals(5, base.withMin(9).min());
        // withMax below min pulls min down to the new max.
        VariantDifficulty lowered = base.withMax(1);
        assertEquals(1, lowered.max());
        assertEquals(1, lowered.min());
        // withMax to ALL leaves min untouched.
        assertEquals(2, base.withMax(VariantDifficulty.ALL).min());
        assertEquals(VariantDifficulty.ALL, base.withMax(VariantDifficulty.ALL).max());
    }

    @Test
    @DisplayName("equals / hashCode by value")
    void equalsByValue() {
        VariantDifficulty a = new VariantDifficulty(2, 5);
        VariantDifficulty b = new VariantDifficulty(2, 5);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new VariantDifficulty(2, 6));
        assertNotEquals(a, VariantDifficulty.NONE);
    }
}
