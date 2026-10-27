package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpheresCometsTest {

    @Test
    void firstLapWaitsForTheVoidSky() {
        assertFalse(SpheresComets.active(0L, 0.0, 1.0));
        assertFalse(SpheresComets.active(0L, 0.49, 1.0));
        assertTrue(SpheresComets.active(0L, 0.5, 1.0));
        assertFalse(SpheresComets.active(0L, 1.0, 0.0), "the End band's own sky is not the spheres band");
        assertEquals(0.0, SpheresComets.chancePerTick(0L, 0.2, 1.0));
    }

    @Test
    void laterLapsFallFromTheBandStart() {
        assertTrue(SpheresComets.active(1L, 0.0, 0.01));
        assertTrue(SpheresComets.active(4L, 0.0, 1.0));
        assertFalse(SpheresComets.active(1L, 1.0, 0.0));
    }

    @Test
    void intervalClimbsEachLapToAFloor() {
        assertEquals(20.0, SpheresComets.intervalSeconds(0L), 1e-9);
        assertEquals(5.0, SpheresComets.intervalSeconds(1L), 1e-9);
        assertEquals(2.5, SpheresComets.intervalSeconds(2L), 1e-9);
        assertEquals(2.0, SpheresComets.intervalSeconds(3L), 1e-9);
        assertEquals(2.0, SpheresComets.intervalSeconds(1_000L), 1e-9);
    }

    @Test
    void chanceIsOnePerIntervalOfTicks() {
        assertEquals(1.0 / 400.0, SpheresComets.chancePerTick(0L, 1.0, 1.0), 1e-12);
        assertEquals(1.0 / 100.0, SpheresComets.chancePerTick(1L, 0.0, 1.0), 1e-12);
    }
}
