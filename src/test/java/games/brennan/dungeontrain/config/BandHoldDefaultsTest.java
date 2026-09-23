package games.brennan.dungeontrain.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the chuncks / spheres / stacks band lengths and the v1 -> v2 migration that carries them to
 * existing installs.
 */
class BandHoldDefaultsTest {

    @Test
    @DisplayName("the chuncks and stacks bands default to 8000 blocks, the spheres band to 12000")
    void bandHoldDefaults() {
        assertEquals(8000, DungeonTrainCommonConfig.DEFAULT_CHUNCKS_HOLD_BLOCKS);
        assertEquals(12000, DungeonTrainCommonConfig.DEFAULT_SPHERES_HOLD_BLOCKS);
        assertEquals(8000, DungeonTrainCommonConfig.DEFAULT_STACKS_HOLD_BLOCKS);
    }

    @Test
    @DisplayName("the v2 -> v3 migration moves exactly the spheres length v2 shipped")
    void spheresV2IsTheLengthV2Shipped() {
        assertEquals(8000, DungeonTrainCommonConfig.SPHERES_V2_HOLD_BLOCKS,
                "the v2 -> v3 migration moves exactly this spheres hold to 12000");
    }

    @Test
    @DisplayName("the migration moves exactly the previously shipped length")
    void legacyIsThePreviouslyShippedLength() {
        assertEquals(5000, DungeonTrainCommonConfig.LEGACY_BAND_HOLD_BLOCKS,
                "the v1 -> v2 migration moves exactly this value to the new defaults; change it and every "
                        + "existing install is either missed or has a deliberate choice overwritten");
    }

    /**
     * Every existing dungeontrain-common.toml has 5000 written to disk, so without a version bump the
     * migration never runs and the longer bands reach only brand-new installs.
     */
    @Test
    @DisplayName("a config migration ships to carry the longer bands to existing installs")
    void aMigrationShipsForTheNewDefaults() {
        assertTrue(DungeonTrainCommonConfig.CURRENT_CONFIG_VERSION >= 3,
                "CURRENT_CONFIG_VERSION must be at least 3, or the v2 -> v3 spheres-hold step never runs");
        assertTrue(DungeonTrainCommonConfig.CURRENT_CONFIG_VERSION
                        <= DungeonTrainCommonConfig.MAX_CONFIG_VERSION,
                "outside the spec's range the value fails validation and NeoForge silently resets "
                        + "it to the default, re-running every migration on every launch");
    }
}
