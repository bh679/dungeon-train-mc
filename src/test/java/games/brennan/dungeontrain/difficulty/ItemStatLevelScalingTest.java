package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for {@link ItemStatLevelScaling#bonusFor} — the flat per-tier
 * primary-stat bonus math. The {@code instanceof AxeItem} classification in
 * {@link ItemStatLevelScaling#primaryStatBonus} needs a Minecraft bootstrap and is
 * verified in-game; here we lock down the rates and the tier-0 floor (mirroring
 * {@link DifficultyProgressionTest}).
 */
final class ItemStatLevelScalingTest {

    private static final double EPS = 1.0e-9;

    @Test
    @DisplayName("default rate 0.10/tier, axe rate 0.15/tier")
    void rates() {
        assertEquals(0.10, ItemStatLevelScaling.perLevelRate(false), EPS);
        assertEquals(0.15, ItemStatLevelScaling.perLevelRate(true), EPS);
    }

    @Test
    @DisplayName("bonus = rate * tier")
    void bonusScalesLinearly() {
        assertEquals(1.0, ItemStatLevelScaling.bonusFor(false, 10), EPS);  // 0.10 * 10
        assertEquals(1.5, ItemStatLevelScaling.bonusFor(true, 10), EPS);   // 0.15 * 10
        assertEquals(0.10, ItemStatLevelScaling.bonusFor(false, 1), EPS);
        assertEquals(0.15, ItemStatLevelScaling.bonusFor(true, 1), EPS);
    }

    @Test
    @DisplayName("no bonus at tier 0 or below")
    void noBonusAtOrBelowZero() {
        assertEquals(0.0, ItemStatLevelScaling.bonusFor(false, 0), EPS);
        assertEquals(0.0, ItemStatLevelScaling.bonusFor(true, 0), EPS);
        assertEquals(0.0, ItemStatLevelScaling.bonusFor(true, -5), EPS);
    }

    // --- pastCapTier: the post-material-cap tier that keeps regular hostile gear scaling
    //     once its material has ceilinged at netherite (MATERIAL_CAP_LEVEL). 0 at/below the
    //     cap (tiers ≤ cap stay a plain roll), then climbs one-for-one. ---

    @Test
    @DisplayName("MATERIAL_CAP_LEVEL is the netherite full-weight level (50)")
    void materialCapLevel_isFifty() {
        assertEquals(50, ProceduralTiers.MATERIAL_CAP_LEVEL);
    }

    @Test
    @DisplayName("pastCapTier: 0 at or below the cap, then climbs one-for-one above it")
    void pastCapTier_zeroUntilCapThenClimbs() {
        int cap = ProceduralTiers.MATERIAL_CAP_LEVEL;
        assertEquals(0, ItemStatLevelScaling.pastCapTier(0, cap));
        assertEquals(0, ItemStatLevelScaling.pastCapTier(cap - 1, cap));
        assertEquals(0, ItemStatLevelScaling.pastCapTier(cap, cap));       // exactly at the cap → still no bonus
        assertEquals(1, ItemStatLevelScaling.pastCapTier(cap + 1, cap));
        assertEquals(25, ItemStatLevelScaling.pastCapTier(cap + 25, cap));
        assertEquals(50, ItemStatLevelScaling.pastCapTier(cap + 50, cap)); // tier 100 → post-cap 50
    }

    @Test
    @DisplayName("pastCapTier: never negative (a tier below a large cap clamps to 0)")
    void pastCapTier_neverNegative() {
        assertEquals(0, ItemStatLevelScaling.pastCapTier(5, 50));
        assertEquals(0, ItemStatLevelScaling.pastCapTier(-3, 50));
    }

    @Test
    @DisplayName("pastCapTier feeds bonusFor: no bonus at/below cap, normal rate resumes above")
    void pastCapTier_composesWithBonusFor() {
        int cap = ProceduralTiers.MATERIAL_CAP_LEVEL;
        // At the cap → post-cap tier 0 → no bonus (identical to a plain AIS roll).
        assertEquals(0.0, ItemStatLevelScaling.bonusFor(false, ItemStatLevelScaling.pastCapTier(cap, cap)), EPS);
        // Tier cap+50 (e.g. 100) → post-cap 50 → +5.0 default, +7.5 axe.
        assertEquals(5.0, ItemStatLevelScaling.bonusFor(false, ItemStatLevelScaling.pastCapTier(cap + 50, cap)), EPS);
        assertEquals(7.5, ItemStatLevelScaling.bonusFor(true, ItemStatLevelScaling.pastCapTier(cap + 50, cap)), EPS);
    }
}
