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
    /** Last entry of the nearby-cap table, held for every lap beyond it (mirrors {@code GHAST_NEARBY_CAPS}). */
    private static final int CAP_MAX = 15;
    /** The spawner's overall band-mob ceiling, which gates the ghast cap (mirrors {@code NEARBY_CAP}). */
    private static final int NEARBY_CAP = 10;

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
    @DisplayName("nearby cap escalates 3, 5, 8, 15 across the first laps")
    void capFor_growsPerLap() {
        assertEquals(3, NetherMobSpawner.ghastCapFor(0));
        assertEquals(5, NetherMobSpawner.ghastCapFor(1));
        assertEquals(8, NetherMobSpawner.ghastCapFor(2));
        assertEquals(15, NetherMobSpawner.ghastCapFor(3));
        assertEquals(15, NetherMobSpawner.ghastCapFor(4));
    }

    @Test
    @DisplayName("nearby cap holds the last table entry and never runs off the end")
    void capFor_clampsAtMax() {
        for (long pass : new long[] {5, 40, 10_000, Integer.MAX_VALUE, Long.MAX_VALUE}) {
            assertEquals(CAP_MAX, NetherMobSpawner.ghastCapFor(pass), "pass " + pass);
        }
    }

    @Test
    @DisplayName("nearby cap is monotonic — a later lap is never less permissive")
    void capFor_monotonic() {
        for (long pass = 0; pass < 12; pass++) {
            assertTrue(NetherMobSpawner.ghastCapFor(pass) <= NetherMobSpawner.ghastCapFor(pass + 1),
                    "cap regressed between pass " + pass + " and " + (pass + 1));
        }
    }

    @Test
    @DisplayName("documents that NEARBY_CAP gates the ghast cap from lap 3 on")
    void capFor_boundedInPracticeByOverallNearbyCap() {
        // The spawner checks `nearby.size() >= NEARBY_CAP` BEFORE any ghast logic, so a ghast cap
        // above 10 never binds — laps 3+ all mean "any of the 10 slots may be a ghast". This is
        // intended (NEARBY_CAP deliberately stays 10); the test pins the interaction so a future
        // NEARBY_CAP change surfaces here rather than silently altering late-lap density.
        assertTrue(NetherMobSpawner.ghastCapFor(0) < NEARBY_CAP, "lap 0 cap should bind below NEARBY_CAP");
        assertTrue(NetherMobSpawner.ghastCapFor(1) < NEARBY_CAP, "lap 1 cap should bind below NEARBY_CAP");
        assertTrue(NetherMobSpawner.ghastCapFor(2) < NEARBY_CAP, "lap 2 cap should bind below NEARBY_CAP");
        assertTrue(NetherMobSpawner.ghastCapFor(3) >= NEARBY_CAP, "lap 3+ is gated by NEARBY_CAP, not the table");
    }

    @Test
    @DisplayName("the -1 band-off sentinel reads as the first lap's cap")
    void capFor_negativePassReadsAsFirstLap() {
        assertEquals(3, NetherMobSpawner.ghastCapFor(-1));
        assertEquals(3, NetherMobSpawner.ghastCapFor(Long.MIN_VALUE));
    }
}
