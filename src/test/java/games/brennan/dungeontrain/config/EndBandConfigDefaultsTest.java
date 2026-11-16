package games.brennan.dungeontrain.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins End-band feature spill on: without it, trees stop at chunk borders and the band reads as a checkerboard. */
class EndBandConfigDefaultsTest {

    @Test
    @DisplayName("feature spill between sampled End-band chunks is on unless a server turns it off")
    void featureSpillDefaultsOn() {
        assertTrue(EndBandConfig.DEFAULT_FEATURE_SPILL);
        assertTrue(EndBandConfig.featureSpill(), "before the config loads the hardcoded default applies");
    }

    @Test
    @DisplayName("the config version is high enough for the v16 -> v17 spill step to run")
    void featureSpillMigrationRuns() {
        assertTrue(DungeonTrainCommonConfig.CURRENT_CONFIG_VERSION >= 17,
                "CURRENT_CONFIG_VERSION must be at least 17, or existing configs keep spill off forever");
        assertTrue(DungeonTrainCommonConfig.CURRENT_CONFIG_VERSION <= DungeonTrainCommonConfig.MAX_CONFIG_VERSION);
    }

    @Test
    @DisplayName("sampled End-band terrain is generated in worldgen unless a server sends it back to the background sampler")
    void terrainDefaultsToWorldgen() {
        assertEquals(EndBandConfig.Terrain.WORLDGEN, EndBandConfig.DEFAULT_TERRAIN);
        assertEquals(EndBandConfig.Terrain.WORLDGEN, EndBandConfig.terrain(), "before the config loads the hardcoded default applies");
        assertTrue(EndBandConfig.terrainInWorldgen());
    }
}
