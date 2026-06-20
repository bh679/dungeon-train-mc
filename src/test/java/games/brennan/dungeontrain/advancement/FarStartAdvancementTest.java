package games.brennan.dungeontrain.advancement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the pure gating decision for "The Far Start"
 * ({@link FarStartAdvancement#shouldGrant}). The full
 * {@code checkAndGrant}/{@code grant} path needs a live {@code ServerPlayer}
 * and {@code ServerAdvancementManager}, so these tests exercise the part that
 * actually encodes the rule — "past the carriage threshold AND still carrying
 * the starting book".
 */
final class FarStartAdvancementTest {

    @Test
    @DisplayName("Below the threshold never grants, book or not")
    void belowThreshold() {
        assertFalse(FarStartAdvancement.shouldGrant(0, true));
        assertFalse(FarStartAdvancement.shouldGrant(FarStartAdvancement.CARRIAGE_THRESHOLD - 1, true));
        assertFalse(FarStartAdvancement.shouldGrant(FarStartAdvancement.CARRIAGE_THRESHOLD - 1, false));
    }

    @Test
    @DisplayName("At/above the threshold grants only while carrying the starting book")
    void atOrAboveThreshold() {
        assertFalse(FarStartAdvancement.shouldGrant(FarStartAdvancement.CARRIAGE_THRESHOLD, false));
        assertFalse(FarStartAdvancement.shouldGrant(FarStartAdvancement.CARRIAGE_THRESHOLD + 50, false));
        assertTrue(FarStartAdvancement.shouldGrant(FarStartAdvancement.CARRIAGE_THRESHOLD, true));
        assertTrue(FarStartAdvancement.shouldGrant(FarStartAdvancement.CARRIAGE_THRESHOLD + 50, true));
    }
}
