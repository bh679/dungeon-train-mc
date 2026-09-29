package games.brennan.dungeontrain.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Distant Horizons LOD-lite decoration default. It ships ON: the skipped features are below
 * LOD resolution and LOD chunks are never saved, so real terrain is untouched (see LodGeneration).
 */
class DistantLodDefaultsTest {

    @Test
    @DisplayName("LOD-lite Nether-core decoration defaults on")
    void lodLiteDefaultsOn() {
        assertTrue(DungeonTrainCommonConfig.DEFAULT_DISTANT_LOD_LITE_DECORATION);
    }
}
