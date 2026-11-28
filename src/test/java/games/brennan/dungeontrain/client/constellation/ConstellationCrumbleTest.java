package games.brennan.dungeontrain.client.constellation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstellationCrumbleTest {

    private static final float CORE = 0.72f;
    private static final double T = 1234.5;

    @Test
    void solidInsideTheCoreAndEmptyFarOut() {
        assertTrue(ConstellationCrumble.on(0, 0, CORE, T));
        assertTrue(ConstellationCrumble.on(3, -4, CORE, T));
        int far = (int) Math.ceil(ConstellationCrumble.extent(CORE) / ConstellationCrumble.CELL) + 2;
        assertFalse(ConstellationCrumble.on(far, 0, CORE, T));
        assertFalse(ConstellationCrumble.on(0, -far, CORE, T));
    }

    @Test
    void noKeptCellStandsAlone() {
        int n = (int) Math.ceil(ConstellationCrumble.extent(CORE) / ConstellationCrumble.CELL);
        for (int i = -n; i < n; i++) {
            for (int j = -n; j < n; j++) {
                if (!ConstellationCrumble.on(i, j, CORE, T)) continue;
                boolean neighbour = ConstellationCrumble.raw(i + 1, j, CORE, T) > 0 || ConstellationCrumble.raw(i - 1, j, CORE, T) > 0
                    || ConstellationCrumble.raw(i, j + 1, CORE, T) > 0 || ConstellationCrumble.raw(i, j - 1, CORE, T) > 0;
                assertTrue(neighbour, "lone cell at " + i + "," + j);
            }
        }
    }

    @Test
    void steadyFrameToFrame() {
        assertEquals(ConstellationCrumble.hash(7, -3, 0), ConstellationCrumble.hash(7, -3, 0));
        assertEquals(ConstellationCrumble.on(17, 4, CORE, T), ConstellationCrumble.on(17, 4, CORE, T));
    }

    @Test
    void threeToFiveArmsAtAnyTime() {
        for (double t = 0; t < 4000; t += 37) {
            int arms = 0;
            for (int k = 0; k < ConstellationCrumble.ARMS; k++) if (ConstellationCrumble.armStrength(k, t) > 0.05f) arms++;
            assertTrue(arms >= 3 && arms <= 5, "arms at " + t + ": " + arms);
        }
    }

    @Test
    void outlineReachesOutInArmsAndChangesSlowly() {
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float a = -3.14f; a < 3.14f; a += 0.02f) {
            float o = ConstellationCrumble.outline(a, T);
            min = Math.min(min, o);
            max = Math.max(max, o);
            assertTrue(Math.abs(o - ConstellationCrumble.outline(a, T + 0.1)) < 0.01f, "jumps at " + a);
        }
        assertEquals(-ConstellationCrumble.INDENT, min, 1e-4f);
        assertTrue(max > 0.2f, "an arm reaches out: " + max);
    }
}
