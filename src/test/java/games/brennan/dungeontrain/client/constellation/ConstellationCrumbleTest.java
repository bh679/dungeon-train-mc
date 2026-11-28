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
        assertTrue(ConstellationCrumble.on(2, -3, CORE, T));
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
    void armsBendThickenAndThickerOnesReachLess() {
        for (double t = 0; t < 4000; t += 23) {
            for (ConstellationCrumble.Arm a : ConstellationCrumble.arms(t)) {
                assertTrue(a.thickness() >= 1f && a.thickness() <= ConstellationCrumble.MAX_THICKNESS);
                assertTrue(Math.abs(a.bend()) <= ConstellationCrumble.BEND);
                assertTrue(a.length() <= ConstellationCrumble.ARM * (1f - 0.2f * (a.thickness() - 1f)) + 1e-4f);
            }
        }
    }

    @Test
    void armsChangeSlowly() {
        ConstellationCrumble.Arm[] now = ConstellationCrumble.arms(T), soon = ConstellationCrumble.arms(T + 0.1);
        assertEquals(now.length, soon.length);
        for (int k = 0; k < now.length; k++) {
            assertTrue(Math.abs(now[k].base() - soon[k].base()) < 0.01f);
            assertTrue(Math.abs(now[k].length() - soon[k].length()) < 0.01f);
        }
    }

    @Test
    void anArmReachesWellPastTheCore() {
        ConstellationCrumble.Arm a = ConstellationCrumble.arms(T)[0];
        float r = CORE + a.length() * 0.3f;
        float angle = a.base() + a.bend() * 0.3f;
        assertTrue(ConstellationCrumble.armAt(r, angle, CORE, new ConstellationCrumble.Arm[] {a}) > 0f);
        assertEquals(0f, ConstellationCrumble.armAt(CORE + a.length() * 1.1f, angle, CORE, new ConstellationCrumble.Arm[] {a}));
    }
}
