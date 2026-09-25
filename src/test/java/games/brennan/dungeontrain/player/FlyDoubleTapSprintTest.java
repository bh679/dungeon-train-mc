package games.brennan.dungeontrain.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Double-tap forward while flying: two fresh presses inside the window start a sprint. */
class FlyDoubleTapSprintTest {

    /** Runs press/release ticks ({@code true} = forward held) and reports whether any tick sprinted. */
    private static boolean sprintsAfter(boolean eligible, boolean... held) {
        int timer = 0;
        boolean was = false;
        for (boolean is : held) {
            FlyDoubleTapSprint.Step step = FlyDoubleTapSprint.tick(timer, was, is, eligible);
            if (step.sprint()) return true;
            timer = step.timer();
            was = is;
        }
        return false;
    }

    @Test
    void quickDoubleTapSprints() {
        assertTrue(sprintsAfter(true, true, false, true));
    }

    @Test
    void secondTapAfterWindowDoesNot() {
        boolean[] ticks = new boolean[FlyDoubleTapSprint.WINDOW_TICKS + 2];
        ticks[0] = true;
        ticks[ticks.length - 1] = true;
        assertFalse(sprintsAfter(true, ticks));
    }

    @Test
    void holdingForwardDoesNot() {
        assertFalse(sprintsAfter(true, true, true, true, true));
    }

    @Test
    void singleTapArmsTheWindow() {
        assertEquals(new FlyDoubleTapSprint.Step(FlyDoubleTapSprint.WINDOW_TICKS, false),
                FlyDoubleTapSprint.tick(0, false, true, true));
    }

    @Test
    void ineligibleClearsTheWindow() {
        assertEquals(new FlyDoubleTapSprint.Step(0, false), FlyDoubleTapSprint.tick(5, false, true, false));
        assertFalse(sprintsAfter(false, true, false, true));
    }
}
