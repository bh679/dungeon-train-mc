package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EditorAutoFlyTest {

    private static final int FLOOR = EditorLayout.PLOT_Y - 1;

    @Test
    @DisplayName("a creative player falling below the frame starts flying")
    void catchesAFallBelowTheFrame() {
        assertTrue(EditorAutoFly.shouldCatch(FLOOR - 0.5, 5f, FLOOR, true, false));
    }

    @Test
    @DisplayName("above the frame, already flying, unable to fly, or just standing low: left alone")
    void leavesEverythingElse() {
        assertFalse(EditorAutoFly.shouldCatch(FLOOR + 1, 5f, FLOOR, true, false), "above the frame");
        assertFalse(EditorAutoFly.shouldCatch(FLOOR - 10, 5f, FLOOR, true, true), "already flying");
        assertFalse(EditorAutoFly.shouldCatch(FLOOR - 10, 5f, FLOOR, false, false), "survival");
        assertFalse(EditorAutoFly.shouldCatch(FLOOR - 10, 0f, FLOOR, true, false), "walking about below, not falling");
    }
}
