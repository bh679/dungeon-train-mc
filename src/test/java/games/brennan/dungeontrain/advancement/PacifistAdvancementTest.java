package games.brennan.dungeontrain.advancement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the pure gating decision for the "Pacifist" chain
 * ({@link PacifistAdvancement#shouldGrant}). The full
 * {@code checkAndGrant}/{@code grant} path needs a live {@code ServerPlayer}
 * and {@code ServerAdvancementManager}, so these tests exercise the part that
 * actually encodes the rule — "past the carriage threshold AND zero damage
 * dealt this life".
 */
final class PacifistAdvancementTest {

    @Test
    @DisplayName("Below the threshold never grants, damage or not")
    void belowThreshold() {
        assertFalse(PacifistAdvancement.shouldGrant(0, 0.0, 100));
        assertFalse(PacifistAdvancement.shouldGrant(99, 0.0, 100));
        assertFalse(PacifistAdvancement.shouldGrant(99, 5.0, 100));
    }

    @Test
    @DisplayName("At/above the threshold grants only with zero damage dealt")
    void atOrAboveThreshold() {
        assertFalse(PacifistAdvancement.shouldGrant(100, 0.1, 100));
        assertFalse(PacifistAdvancement.shouldGrant(1000, 5.0, 100));
        assertTrue(PacifistAdvancement.shouldGrant(100, 0.0, 100));
        assertTrue(PacifistAdvancement.shouldGrant(250, 0.0, 100));
    }

    @Test
    @DisplayName("Any damage dealt this life blocks every tier, not just the current one")
    void anyDamageBlocksAllTiers() {
        assertFalse(PacifistAdvancement.shouldGrant(1000, 0.5, 100));
        assertFalse(PacifistAdvancement.shouldGrant(1000, 0.5, 250));
        assertFalse(PacifistAdvancement.shouldGrant(1000, 0.5, 1000));
    }
}
