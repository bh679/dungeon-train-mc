package games.brennan.dungeontrain.client.constellation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstellationCrumbleTest {

    private static final float CORE = 0.72f;

    @Test
    void solidInsideTheCoreAndEmptyFarOut() {
        assertTrue(ConstellationCrumble.on(0, 0, CORE));
        assertTrue(ConstellationCrumble.on(5, -6, CORE));
        int far = (int) Math.ceil(ConstellationCrumble.extent(CORE) / ConstellationCrumble.CELL) + 2;
        assertFalse(ConstellationCrumble.on(far, 0, CORE));
        assertFalse(ConstellationCrumble.on(0, -far, CORE));
    }

    @Test
    void noKeptCellStandsAlone() {
        int n = (int) Math.ceil(ConstellationCrumble.extent(CORE) / ConstellationCrumble.CELL);
        for (int i = -n; i < n; i++) {
            for (int j = -n; j < n; j++) {
                if (!ConstellationCrumble.on(i, j, CORE)) continue;
                boolean neighbour = ConstellationCrumble.raw(i + 1, j, CORE) > 0 || ConstellationCrumble.raw(i - 1, j, CORE) > 0
                    || ConstellationCrumble.raw(i, j + 1, CORE) > 0 || ConstellationCrumble.raw(i, j - 1, CORE) > 0;
                assertTrue(neighbour, "lone cell at " + i + "," + j);
            }
        }
    }

    @Test
    void steadyFrameToFrame() {
        assertEquals(ConstellationCrumble.hash(7, -3, 0), ConstellationCrumble.hash(7, -3, 0));
        assertEquals(ConstellationCrumble.on(17, 4, CORE), ConstellationCrumble.on(17, 4, CORE));
    }

    @Test
    void outlineOnlyReachesOutward() {
        for (float a = -3.2f; a < 3.2f; a += 0.1f) {
            float w = ConstellationCrumble.wobble(a);
            assertTrue(w >= 0f && w <= ConstellationCrumble.WOBBLE);
        }
    }
}
