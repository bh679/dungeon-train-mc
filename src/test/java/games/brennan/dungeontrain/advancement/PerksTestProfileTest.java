package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.perks.PerksGate;
import games.brennan.dungeontrain.player.DifficultyPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A perks test world keeps its advancements across test worlds like a live run, but only in the test
 * profile: {@link AdvancementProfiles#persists(boolean, boolean, String)}.
 */
final class PerksTestProfileTest {

    private static final String NORMAL = DifficultyPartition.LEGACY_KEY;

    @Test
    @DisplayName("a test world banks into the test profile, never into a real one")
    void testWorldBanksOnlyIntoTestProfile() {
        assertTrue(AdvancementProfiles.persists(false, true, PerksGate.TEST_PROFILE));
        assertFalse(AdvancementProfiles.persists(false, true, NORMAL), "test earns must never reach Normal");
        assertFalse(AdvancementProfiles.persists(false, true, "hard"));
    }

    @Test
    @DisplayName("ordinary Free Play still banks nothing; clean runs and editor authoring are unchanged")
    void ordinaryFreePlayUnchanged() {
        assertFalse(AdvancementProfiles.persists(false, false, NORMAL));
        assertFalse(AdvancementProfiles.persists(false, false, PerksGate.TEST_PROFILE));
        assertTrue(AdvancementProfiles.persists(true, false, NORMAL));
        assertTrue(AdvancementProfiles.persists(true, false, GlobalAchievementStore.SHARED));
    }

    @Test
    @DisplayName("the test profile has its own files, apart from Normal's")
    void ownFiles() {
        UUID u = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        assertEquals(u + ".perktest.json", GlobalAchievementStore.fileName(u, PerksGate.TEST_PROFILE));
        assertEquals(u + ".perktest.criteria.json", GlobalCriteriaProgress.fileName(u, PerksGate.TEST_PROFILE));
        assertEquals(u + ".json", GlobalAchievementStore.fileName(u, NORMAL));
    }
}
