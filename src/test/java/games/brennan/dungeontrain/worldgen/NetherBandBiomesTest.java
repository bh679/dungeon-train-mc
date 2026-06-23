package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure altitude-zone + region-pick math behind the highland biome override
 * ({@link NetherBandBiomes}). The biome {@code Holder}s themselves need a registry, but the zone
 * boundaries and deterministic in-zone selection are pure and testable.
 */
final class NetherBandBiomesTest {

    @Test
    @DisplayName("altitude zones: base < 100 ≤ mid < 145 ≤ high < 175 ≤ peak")
    void zones() {
        assertEquals(0, NetherBandBiomes.zoneIndex(63), "sea level → base");
        assertEquals(0, NetherBandBiomes.zoneIndex(99));
        assertEquals(1, NetherBandBiomes.zoneIndex(100), "MID_Y → mid");
        assertEquals(1, NetherBandBiomes.zoneIndex(144));
        assertEquals(2, NetherBandBiomes.zoneIndex(145), "HIGH_Y → high");
        assertEquals(2, NetherBandBiomes.zoneIndex(174));
        assertEquals(3, NetherBandBiomes.zoneIndex(175), "PEAK_Y → peak (bare caps)");
        assertEquals(3, NetherBandBiomes.zoneIndex(319));
    }

    @Test
    @DisplayName("every zone has at least one biome and zone indices cover 0..3")
    void zonesPopulated() {
        assertEquals(4, NetherBandBiomes.ZONES.size());
        for (int z = 0; z < 4; z++) {
            assertTrue(!NetherBandBiomes.ZONES.get(z).isEmpty(), "zone " + z + " must have a biome");
        }
    }

    @Test
    @DisplayName("in-zone pick is always within range and stable across a 64-block region")
    void pickRangeAndCoherence() {
        long seed = 0xC0FFEEL;
        for (int size = 1; size <= 5; size++) {
            for (int x = -200; x <= 200; x += 7) {
                for (int z = -200; z <= 200; z += 13) {
                    int p = NetherBandBiomes.pickWithinZone(seed, x, z, size);
                    assertTrue(p >= 0 && p < size, "pick out of range: " + p + " size=" + size);
                }
            }
        }
        // Coherence: same 64-block region (same x>>6, z>>6) → same pick.
        assertEquals(NetherBandBiomes.pickWithinZone(seed, 0, 0, 4),
                NetherBandBiomes.pickWithinZone(seed, 63, 63, 4), "same region should pick the same biome");
    }

    @Test
    @DisplayName("size ≤ 1 always picks index 0 (single-biome zones like snowy_slopes)")
    void singletonZone() {
        assertEquals(0, NetherBandBiomes.pickWithinZone(123L, 5000, 0, 1));
        assertEquals(0, NetherBandBiomes.pickWithinZone(123L, 9999, -42, 1));
    }

    @Test
    @DisplayName("different seeds vary the region pick (so worlds differ)")
    void seedVaries() {
        boolean differs = false;
        for (int region = 0; region < 32 && !differs; region++) {
            int a = NetherBandBiomes.pickWithinZone(1L, region << 6, 0, 4);
            int b = NetherBandBiomes.pickWithinZone(2L, region << 6, 0, 4);
            if (a != b) differs = true;
        }
        assertTrue(differs, "distinct seeds should produce distinct biome layouts");
    }
}
