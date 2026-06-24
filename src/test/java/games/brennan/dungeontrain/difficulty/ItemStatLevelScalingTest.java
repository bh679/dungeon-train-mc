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
}
