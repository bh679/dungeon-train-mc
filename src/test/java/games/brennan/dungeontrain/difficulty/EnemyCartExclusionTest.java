package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The no-hostiles opening stretch keeps enemy carts out by carriage position. */
class EnemyCartExclusionTest {

    @Test
    @DisplayName("the first N carriages exclude enemy carts, the N-th onward does not")
    void firstNCarriagesExclude() {
        assertTrue(DifficultyProgression.excludesEnemyCarts(0, true, 5));
        assertTrue(DifficultyProgression.excludesEnemyCarts(4, true, 5));
        assertFalse(DifficultyProgression.excludesEnemyCarts(5, true, 5));
    }

    @Test
    @DisplayName("backward carriages ramp the same as forward ones")
    void backwardIsSymmetric() {
        assertTrue(DifficultyProgression.excludesEnemyCarts(-4, true, 5));
        assertFalse(DifficultyProgression.excludesEnemyCarts(-5, true, 5));
    }

    @Test
    @DisplayName("stage off, or zero length, excludes nothing")
    void disabledExcludesNothing() {
        assertFalse(DifficultyProgression.excludesEnemyCarts(0, false, 5));
        assertFalse(DifficultyProgression.excludesEnemyCarts(0, true, 0));
        assertFalse(DifficultyProgression.excludesEnemyCarts(0, true, -3));
    }
}
