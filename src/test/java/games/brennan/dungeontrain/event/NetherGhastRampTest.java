package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic unit tests for the per-lap ghast ramp in {@link NetherMobSpawner} —
 * {@link NetherMobSpawner#ghastDenomFor} (odds, one step denser per lap) and
 * {@link NetherMobSpawner#ghastCapFor} (how many may be alive near a player). No NeoForge
 * bootstrap: both methods take the biome class and pass index as parameters rather than reading
 * a {@code Holder<Biome>} or live config, mirroring the {@code DifficultyProgressionTest} pattern
 * of exercising the pure helper instead of the config-reading wrapper.
 */
final class NetherGhastRampTest {

    /** Densest the odds ever get (mirrors {@code NetherMobSpawner.GHAST_DENOM_FLOOR}). */
    private static final int FLOOR = 2;
    /** Ceiling the nearby-cap escalates to (mirrors {@code GHAST_NEARBY_CAP_MAX}). */
    private static final int CAP_MAX = 6;

    @Test
    @DisplayName("dense biomes: 1-in-4 on the first pass, one step denser per lap")
    void denomFor_denseBiomes_stepsDownPerLap() {
        assertEquals(4, NetherMobSpawner.ghastDenomFor(true, 0));
        assertEquals(3, NetherMobSpawner.ghastDenomFor(true, 1));
        assertEquals(2, NetherMobSpawner.ghastDenomFor(true, 2));
    }

    @Test
    @DisplayName("other biomes: 1-in-8 on the first pass, one step denser per lap")
    void denomFor_otherBiomes_stepsDownPerLap() {
        assertEquals(8, NetherMobSpawner.ghastDenomFor(false, 0));
        assertEquals(7, NetherMobSpawner.ghastDenomFor(false, 1));
        assertEquals(6, NetherMobSpawner.ghastDenomFor(false, 2));
        assertEquals(5, NetherMobSpawner.ghastDenomFor(false, 3));
        assertEquals(4, NetherMobSpawner.ghastDenomFor(false, 4));
        assertEquals(3, NetherMobSpawner.ghastDenomFor(false, 5));
        assertEquals(2, NetherMobSpawner.ghastDenomFor(false, 6));
    }

    @Test
    @DisplayName("odds clamp at the floor and never invert, however many laps deep")
    void denomFor_clampsAtFloor() {
        for (long pass : new long[] {7, 50, 10_000, Integer.MAX_VALUE, Long.MAX_VALUE}) {
            assertEquals(FLOOR, NetherMobSpawner.ghastDenomFor(true, pass),
                    "dense biome at pass " + pass);
            assertEquals(FLOOR, NetherMobSpawner.ghastDenomFor(false, pass),
                    "other biome at pass " + pass);
        }
    }

    @Test
    @DisplayName("the -1 band-off sentinel (and any negative pass) reads as the first lap")
    void denomFor_negativePassReadsAsFirstLap() {
        // NetherBand.netherPassIndex returns -1 when the nether phase is off or X precedes the cycle.
        assertEquals(NetherMobSpawner.ghastDenomFor(true, 0), NetherMobSpawner.ghastDenomFor(true, -1));
        assertEquals(NetherMobSpawner.ghastDenomFor(false, 0), NetherMobSpawner.ghastDenomFor(false, -1));
        assertEquals(NetherMobSpawner.ghastDenomFor(false, 0), NetherMobSpawner.ghastDenomFor(false, Long.MIN_VALUE));
    }

    @Test
    @DisplayName("dense biomes are never sparser than other biomes at the same lap")
    void denomFor_denseNeverSparserThanOther() {
        for (long pass = 0; pass <= 12; pass++) {
            assertTrue(NetherMobSpawner.ghastDenomFor(true, pass) <= NetherMobSpawner.ghastDenomFor(false, pass),
                    "dense should be at least as dense as other at pass " + pass);
        }
    }

    @Test
    @DisplayName("nearby cap starts at 2 and grows one per lap")
    void capFor_growsPerLap() {
        assertEquals(2, NetherMobSpawner.ghastCapFor(0));
        assertEquals(3, NetherMobSpawner.ghastCapFor(1));
        assertEquals(4, NetherMobSpawner.ghastCapFor(2));
        assertEquals(5, NetherMobSpawner.ghastCapFor(3));
        assertEquals(6, NetherMobSpawner.ghastCapFor(4));
    }

    @Test
    @DisplayName("nearby cap clamps at the max and never overflows on an absurd lap")
    void capFor_clampsAtMax() {
        for (long pass : new long[] {5, 40, 10_000, Integer.MAX_VALUE, Long.MAX_VALUE}) {
            assertEquals(CAP_MAX, NetherMobSpawner.ghastCapFor(pass), "pass " + pass);
        }
    }

    @Test
    @DisplayName("the -1 band-off sentinel reads as the first lap's cap")
    void capFor_negativePassReadsAsFirstLap() {
        assertEquals(NetherMobSpawner.ghastCapFor(0), NetherMobSpawner.ghastCapFor(-1));
        assertEquals(NetherMobSpawner.ghastCapFor(0), NetherMobSpawner.ghastCapFor(Long.MIN_VALUE));
    }
}
