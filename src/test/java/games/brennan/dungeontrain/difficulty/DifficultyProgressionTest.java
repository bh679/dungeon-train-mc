package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure-logic unit tests for {@link DifficultyProgression#rawTier} (geometric tier math)
 * and {@link DifficultyProgression#effectiveTier} (the progression-delay shift). No
 * Forge / NeoForge bootstrap — the methods under test take {@code carriagesPerTier} and
 * {@code delay} as parameters, mirroring the {@code VillagerTrainSpawnLevelTest} pattern
 * of exercising the pure helper rather than the config-reading wrapper.
 */
final class DifficultyProgressionTest {

    /** Default carriagesPerTier (matches {@code DungeonTrainConfig.DEFAULT_CARRIAGES_PER_TIER}). */
    private static final int CPT = 20;

    @Test
    @DisplayName("rawTier = floor(abs(travelled) / carriagesPerTier)")
    void rawTier_geometric() {
        assertEquals(0, DifficultyProgression.rawTier(0, CPT));
        assertEquals(0, DifficultyProgression.rawTier(19, CPT));
        assertEquals(1, DifficultyProgression.rawTier(20, CPT));
        assertEquals(1, DifficultyProgression.rawTier(39, CPT));
        assertEquals(2, DifficultyProgression.rawTier(40, CPT));
        assertEquals(5, DifficultyProgression.rawTier(100, CPT));
    }

    @Test
    @DisplayName("rawTier uses absolute value (backward travel scales identically)")
    void rawTier_usesAbs() {
        assertEquals(0, DifficultyProgression.rawTier(-19, CPT));
        assertEquals(1, DifficultyProgression.rawTier(-20, CPT));
        assertEquals(2, DifficultyProgression.rawTier(-40, CPT));
    }

    @Test
    @DisplayName("rawTier guards against a zero/negative carriagesPerTier (no div-by-zero)")
    void rawTier_guardsDivisor() {
        assertEquals(20, DifficultyProgression.rawTier(20, 0));
        assertEquals(20, DifficultyProgression.rawTier(20, -5));
    }

    @Test
    @DisplayName("effectiveTier with delay=1 shifts the whole curve back one level")
    void effectiveTier_delayOne() {
        assertEquals(0, DifficultyProgression.effectiveTier(0, CPT, 1));
        assertEquals(0, DifficultyProgression.effectiveTier(19, CPT, 1));
        assertEquals(0, DifficultyProgression.effectiveTier(20, CPT, 1));  // raw 1 → 0
        assertEquals(0, DifficultyProgression.effectiveTier(39, CPT, 1));
        assertEquals(1, DifficultyProgression.effectiveTier(40, CPT, 1));  // raw 2 → 1
        assertEquals(2, DifficultyProgression.effectiveTier(60, CPT, 1));  // raw 3 → 2
    }

    @Test
    @DisplayName("effectiveTier with delay=0 equals rawTier (original curve)")
    void effectiveTier_delayZeroIsRaw() {
        for (int travelled = 0; travelled <= 200; travelled += 7) {
            assertEquals(DifficultyProgression.rawTier(travelled, CPT),
                    DifficultyProgression.effectiveTier(travelled, CPT, 0),
                    "delay=0 should equal rawTier at travelled=" + travelled);
        }
    }

    @Test
    @DisplayName("effectiveTier clamps at 0 — a delay larger than rawTier never goes negative")
    void effectiveTier_clampsAtZero() {
        assertEquals(0, DifficultyProgression.effectiveTier(40, CPT, 5));  // raw 2, delay 5 → 0
        assertEquals(0, DifficultyProgression.effectiveTier(0, CPT, 3));
        assertEquals(0, DifficultyProgression.effectiveTier(19, CPT, 100));
    }

    @Test
    @DisplayName("effectiveTier treats a negative delay as no delay (clamped to 0)")
    void effectiveTier_negativeDelayIsNoDelay() {
        assertEquals(DifficultyProgression.rawTier(60, CPT),
                DifficultyProgression.effectiveTier(60, CPT, -3));
    }

    @Test
    @DisplayName("effectiveTier shifts down by exactly `delay` levels above the floor")
    void effectiveTier_shiftsByDelay() {
        // raw tier 4 (travelled 80): shift by 2 → 2, by 3 → 1, by 4 → 0, by 6 → 0
        assertEquals(2, DifficultyProgression.effectiveTier(80, CPT, 2));
        assertEquals(1, DifficultyProgression.effectiveTier(80, CPT, 3));
        assertEquals(0, DifficultyProgression.effectiveTier(80, CPT, 4));
        assertEquals(0, DifficultyProgression.effectiveTier(80, CPT, 6));
    }

    @Test
    @DisplayName("downgradeLootId swaps the rich loot prefabs to starter when active")
    void downgradeLootId_swapsRichWhenActive() {
        assertEquals("starter", DifficultyProgression.downgradeLootId("loot", true));
        assertEquals("starter", DifficultyProgression.downgradeLootId("loot_irongold", true));
    }

    @Test
    @DisplayName("downgradeLootId matches the rich prefab ids case-insensitively")
    void downgradeLootId_caseInsensitive() {
        assertEquals("starter", DifficultyProgression.downgradeLootId("LOOT", true));
        assertEquals("starter", DifficultyProgression.downgradeLootId("Loot_IronGold", true));
    }

    @Test
    @DisplayName("downgradeLootId leaves non-rich prefabs untouched even when active")
    void downgradeLootId_leavesOthersUntouched() {
        assertEquals("wood", DifficultyProgression.downgradeLootId("wood", true));
        assertEquals("mining", DifficultyProgression.downgradeLootId("mining", true));
        assertEquals("villager", DifficultyProgression.downgradeLootId("villager", true));
        // already the starter prefab → unchanged
        assertEquals("starter", DifficultyProgression.downgradeLootId("starter", true));
    }

    @Test
    @DisplayName("downgradeLootId is a no-op when inactive (past the first band / toggle off)")
    void downgradeLootId_noopWhenInactive() {
        assertEquals("loot", DifficultyProgression.downgradeLootId("loot", false));
        assertEquals("loot_irongold", DifficultyProgression.downgradeLootId("loot_irongold", false));
    }

    @Test
    @DisplayName("downgradeLootId is null-safe (empty chest cell, no linked prefab)")
    void downgradeLootId_nullSafe() {
        assertNull(DifficultyProgression.downgradeLootId(null, true));
        assertNull(DifficultyProgression.downgradeLootId(null, false));
    }
}
