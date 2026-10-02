package games.brennan.dungeontrain.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the End-band feature spill to opt-in: existing worlds keep the look they were generated with. */
class EndBandConfigDefaultsTest {

    @Test
    @DisplayName("feature spill between sampled End-band chunks is off unless a server turns it on")
    void featureSpillDefaultsOff() {
        assertFalse(EndBandConfig.DEFAULT_FEATURE_SPILL);
        assertFalse(EndBandConfig.featureSpill(), "before the config loads the hardcoded default applies");
    }

    @Test
    @DisplayName("sampled End-band terrain is generated in worldgen unless a server sends it back to the background sampler")
    void terrainDefaultsToWorldgen() {
        assertEquals(EndBandConfig.Terrain.WORLDGEN, EndBandConfig.DEFAULT_TERRAIN);
        assertEquals(EndBandConfig.Terrain.WORLDGEN, EndBandConfig.terrain(), "before the config loads the hardcoded default applies");
        assertTrue(EndBandConfig.terrainInWorldgen());
    }
}
