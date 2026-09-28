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
    @DisplayName("the chuncks and stacks bands default to 8000 blocks, the spheres band to 6550")
    void bandHoldDefaults() {
        assertEquals(8000, DungeonTrainCommonConfig.DEFAULT_CHUNCKS_HOLD_BLOCKS);
        assertEquals(6550, DungeonTrainCommonConfig.DEFAULT_SPHERES_HOLD_BLOCKS);
        assertEquals(8000, DungeonTrainCommonConfig.DEFAULT_STACKS_HOLD_BLOCKS);
    }

    @Test
    @DisplayName("the v2 -> v3 migration moves exactly the spheres length v2 shipped")
    void spheresV2IsTheLengthV2Shipped() {
        assertEquals(8000, DungeonTrainCommonConfig.SPHERES_V2_HOLD_BLOCKS,
                "the v2 -> v3 migration moves exactly this spheres hold to the current default");
    }

    @Test
    @DisplayName("the v3 -> v4 migration moves exactly the spheres length and End-sky start v3 shipped")
    void spheresV3IsWhatV3Shipped() {
        assertEquals(12000, DungeonTrainCommonConfig.SPHERES_V3_HOLD_BLOCKS);
        assertEquals(4000, DungeonTrainCommonConfig.SPHERES_V3_END_SKY_START_BLOCKS);
        assertTrue(DungeonTrainCommonConfig.CURRENT_CONFIG_VERSION >= 4,
                "CURRENT_CONFIG_VERSION must be at least 4, or the v3 -> v4 spheres step never runs");
    }

    @Test
    @DisplayName("the v5 -> v6 migration moves exactly what v5 shipped to the shorter spheres band")
    void spheresV5IsWhatV5Shipped() {
        assertEquals(14000, DungeonTrainCommonConfig.SPHERES_V5_HOLD_BLOCKS);
        assertEquals(1500, DungeonTrainCommonConfig.SPHERES_V5_FADE_BLOCKS);
        assertEquals(3000, DungeonTrainCommonConfig.SPHERES_V5_END_SKY_START_BLOCKS);
        assertEquals(5000, SpheresProgressionConfig.V5_NETHER_MIX_START_BLOCKS);
        assertEquals(6000, SpheresProgressionConfig.V5_END_MIX_START_BLOCKS);
        assertEquals(9000, SpheresProgressionConfig.V5_STRUCTURE_BOOST_START_BLOCKS);
        assertEquals(12000, SpheresProgressionConfig.V5_STRUCTURE_BOOST_END_BLOCKS);

        assertEquals(750, DungeonTrainCommonConfig.DEFAULT_SPHERES_FADE_BLOCKS);
        assertEquals(1000, DungeonTrainCommonConfig.DEFAULT_SPHERES_END_SKY_START_BLOCKS);
        assertTrue(DungeonTrainCommonConfig.CURRENT_CONFIG_VERSION >= 6,
                "CURRENT_CONFIG_VERSION must be at least 6, or the v5 -> v6 spheres step never runs");
    }

    @Test
    @DisplayName("each shipped cycle order differs from the next only in the slot its migration changed")
    void cycleOrdersChainToTheDefault() {
        assertEquals(DungeonTrainCommonConfig.V6_WORLDGEN_CYCLE_ORDER,
                DungeonTrainCommonConfig.V5_WORLDGEN_CYCLE_ORDER.replace("spheres:15000", "spheres:6550"),
                "v5 -> v6 changed only the spheres slot");
        assertEquals(DungeonTrainCommonConfig.V7_WORLDGEN_CYCLE_ORDER,
                DungeonTrainCommonConfig.V6_WORLDGEN_CYCLE_ORDER
                        .replace("spheres:6550, ow:5000,", "spheres:6550, ow:sunk:500,")
                        .replace("beta=5000", "beta=3500"),
                "v6 -> v7 changed only the gap into Amplified and the Beta era");
        assertTrue(DungeonTrainCommonConfig.CURRENT_CONFIG_VERSION >= 7,
                "CURRENT_CONFIG_VERSION must be at least 7, or the v6 -> v7 order step never runs");
        assertEquals(DungeonTrainCommonConfig.V8_WORLDGEN_CYCLE_ORDER,
                DungeonTrainCommonConfig.V7_WORLDGEN_CYCLE_ORDER
                        .replace("ow:2000, chuncks:5000, ow:5000, stacks:5000", "ow:650, chuncks:5000, stacks:5000"),
                "v7 -> v8 changed only the gaps after the legacy run and between chuncks and stacks");
        assertTrue(DungeonTrainCommonConfig.CURRENT_CONFIG_VERSION >= 8,
                "CURRENT_CONFIG_VERSION must be at least 8, or the v7 -> v8 order step never runs");
        assertEquals(DungeonTrainCommonConfig.V9_WORLDGEN_CYCLE_ORDER,
                DungeonTrainCommonConfig.V8_WORLDGEN_CYCLE_ORDER
                        .replace("chuncks:5000, stacks:5000", "chuncks:2000, mix:4000, stacks:5000"),
                "v8 -> v9 changed only the chuncks core and added the mix zone");
        assertTrue(DungeonTrainCommonConfig.CURRENT_CONFIG_VERSION >= 9,
                "CURRENT_CONFIG_VERSION must be at least 9, or the v8 -> v9 order step never runs");
        assertEquals(DungeonTrainCommonConfig.V10_WORLDGEN_CYCLE_ORDER,
                DungeonTrainCommonConfig.V9_WORLDGEN_CYCLE_ORDER
                        .replace("nether:3000,", "nether:vanilla>bop:3000,")
                        .replace("end:3000,", "end:vanilla>bop:3000,"),
                "v9 -> v10 changed only Lap 1's Nether and End");
        assertTrue(DungeonTrainCommonConfig.CURRENT_CONFIG_VERSION >= 10,
                "CURRENT_CONFIG_VERSION must be at least 10, or the v9 -> v10 order step never runs");
        assertEquals(DungeonTrainCommonConfig.V11_WORLDGEN_CYCLE_ORDER,
                DungeonTrainCommonConfig.V10_WORLDGEN_CYCLE_ORDER
                        .replace("amplified=5000:beta=3500", "amplified=5000:lost_city=4000:beta=3500"),
                "v10 -> v11 changed only the legacy run, adding the Lost City era after Amplified");
        assertTrue(DungeonTrainCommonConfig.CURRENT_CONFIG_VERSION >= 11,
                "CURRENT_CONFIG_VERSION must be at least 11, or the v10 -> v11 order step never runs");
        assertEquals(DungeonTrainCommonConfig.V12_WORLDGEN_CYCLE_ORDER,
                DungeonTrainCommonConfig.V11_WORLDGEN_CYCLE_ORDER
                        .replace("ow:3000, end:vanilla>bop:3000, upside_down:2500:6000, ow:wwoo:8000, nether:better:8000, ow:bop:8000, end:better:8000,",
                                "ow:wwoo:4500, end:vanilla:1200, end:bop:2000, upside_down:2500:6000, ow:bop:8000, nether:better:8000, legacy:lost_city=4000, end:better:8000,")
                        .replace("amplified=5000:lost_city=4000:beta=3500", "amplified=5000:beta=3500"),
                "v11 -> v12 reordered the laps only: WWOO + a vanilla/BoP End on Lap 1, Lost City on Lap 2");
        assertTrue(DungeonTrainCommonConfig.CURRENT_CONFIG_VERSION >= 12,
                "CURRENT_CONFIG_VERSION must be at least 12, or the v11 -> v12 order step never runs");
        assertEquals(DungeonTrainCommonConfig.V13_WORLDGEN_CYCLE_ORDER,
                DungeonTrainCommonConfig.V12_WORLDGEN_CYCLE_ORDER
                        .replace("legacy:lost_city=4000", "legacy:wwoo:lost_city=4000"),
                "v12 -> v13 changed only the Lost City run's look");
        assertTrue(DungeonTrainCommonConfig.CURRENT_CONFIG_VERSION >= 13,
                "CURRENT_CONFIG_VERSION must be at least 13, or the v12 -> v13 order step never runs");
        assertEquals(DungeonTrainCommonConfig.DEFAULT_WORLDGEN_CYCLE_ORDER,
                DungeonTrainCommonConfig.V13_WORLDGEN_CYCLE_ORDER
                        .replace("upside_down:2500:6000", "upside_down:2500:5000"),
                "v13 -> v14 changed only the upside-down's Reassembly length");
        assertTrue(DungeonTrainCommonConfig.CURRENT_CONFIG_VERSION >= 14,
                "CURRENT_CONFIG_VERSION must be at least 14, or the v13 -> v14 order step never runs");
        assertTrue(DungeonTrainCommonConfig.DEFAULT_WORLDGEN_CYCLE_ORDER.contains("spheres:"
                + DungeonTrainCommonConfig.DEFAULT_SPHERES_HOLD_BLOCKS + ","));
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
