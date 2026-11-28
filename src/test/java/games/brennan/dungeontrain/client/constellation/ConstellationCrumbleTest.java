package games.brennan.dungeontrain.client.constellation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstellationCrumbleTest {

    private static final float CORE = 0.72f;
    private static final double T = 1234.5;

    @Test
    void solidInsideTheMiddleAndEmptyFarOut() {
        assertTrue(ConstellationCrumble.on(0, 0, CORE, T));
        assertTrue(ConstellationCrumble.on(2, -3, CORE, T));
        int far = (int) Math.ceil(ConstellationCrumble.extent(CORE) / ConstellationCrumble.CELL) + 2;
        assertFalse(ConstellationCrumble.on(far, 0, CORE, T));
        assertFalse(ConstellationCrumble.on(0, -far, CORE, T));
    }

    @Test
    void noKeptCellStandsAlone() {
        ConstellationCrumble.Arm[] arms = ConstellationCrumble.arms(CORE, T);
        int n = (int) Math.ceil(ConstellationCrumble.extent(CORE) / ConstellationCrumble.CELL);
        for (int i = -n; i < n; i++) {
            for (int j = -n; j < n; j++) {
                if (!ConstellationCrumble.on(i, j, CORE, arms, T)) continue;
                if (ConstellationCrumble.raw(i, j, CORE, arms, T) >= 1f) continue;
                boolean neighbour = ConstellationCrumble.raw(i + 1, j, CORE, arms, T) > 0
                    || ConstellationCrumble.raw(i - 1, j, CORE, arms, T) > 0
                    || ConstellationCrumble.raw(i, j + 1, CORE, arms, T) > 0
                    || ConstellationCrumble.raw(i, j - 1, CORE, arms, T) > 0;
                assertTrue(neighbour, "lone cell at " + i + "," + j);
            }
        }
    }

    @Test
    void threeToFiveArmsAtAnyTime() {
        for (double t = 0; t < 4000; t += 37) {
            int arms = ConstellationCrumble.arms(CORE, t).length;
            assertTrue(arms >= 3 && arms <= 5, "arms at " + t + ": " + arms);
        }
    }

    @Test
    void armsStartOnTheCircleAndNeverOutreachTheirLength() {
        for (double t = 0; t < 3000; t += 41) {
            for (ConstellationCrumble.Arm a : ConstellationCrumble.arms(CORE, t)) {
                assertEquals(CORE, (float) Math.hypot(a.xs()[0], a.ys()[0]), 1e-4f);
                double length = 0;
                for (int q = 0; q < ConstellationCrumble.SEGMENTS; q++) {
                    length += Math.hypot(a.xs()[q + 1] - a.xs()[q], a.ys()[q + 1] - a.ys()[q]);
                }
                assertTrue(length <= ConstellationCrumble.ARM + 1e-4, "too long: " + length);
                assertTrue(a.thickness() >= 1f && a.thickness() <= ConstellationCrumble.MAX_THICKNESS);
            }
        }
    }

    @Test
    void anArmIsSolidAlongItsLineAndNotPastItsTip() {
        ConstellationCrumble.Arm a = ConstellationCrumble.arms(CORE, T)[0];
        ConstellationCrumble.Arm[] one = {a};
        assertTrue(ConstellationCrumble.armAt(a.xs()[2], a.ys()[2], one) > 0f);
        int last = ConstellationCrumble.SEGMENTS;
        float dx = a.xs()[last] - a.xs()[last - 1], dy = a.ys()[last] - a.ys()[last - 1];
        float far = 0.5f / (float) Math.hypot(dx, dy);
        assertEquals(0f, ConstellationCrumble.armAt(a.xs()[last] + dx * far, a.ys()[last] + dy * far, one));
    }

    @Test
    void edgeWavesStayWithinTheirReachAndChangeSmoothly() {
        float bound = 0;
        for (float a = -3.14f; a < 3.14f; a += 0.05f) {
            float w = ConstellationCrumble.wave(a, T);
            bound = Math.max(bound, Math.abs(w));
            assertTrue(Math.abs(w - ConstellationCrumble.wave(a, T + 0.1)) < 0.01f);
        }
        assertTrue(bound > 0.02f && bound < 0.4f, "wave reach " + bound);
    }

    @Test
    void steadyFrameToFrame() {
        assertEquals(ConstellationCrumble.hash(7, -3, 0), ConstellationCrumble.hash(7, -3, 0));
        assertEquals(ConstellationCrumble.on(17, 4, CORE, T), ConstellationCrumble.on(17, 4, CORE, T));
    }

    @Test
    void speedNeverGoesBelowZero() {
        double keep = ConstellationCrumble.speed();
        try {
            ConstellationCrumble.setSpeed(-3);
            assertEquals(0.0, ConstellationCrumble.speed());
            ConstellationCrumble.setSpeed(2.5);
            assertEquals(2.5, ConstellationCrumble.speed());
        } finally {
            ConstellationCrumble.setSpeed(keep);
        }
    }
}
