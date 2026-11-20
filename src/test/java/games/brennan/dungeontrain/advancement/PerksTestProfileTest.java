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
 * Test mode keeps its advancements across test worlds like a live run, but only in the test profile,
 * and Test plus Free Play keeps nothing: {@link AdvancementProfiles#persists(boolean, boolean, String)}.
 */
final class PerksTestProfileTest {

    private static final String NORMAL = DifficultyPartition.LEGACY_KEY;

    @Test
    @DisplayName("Test mode banks into the test profile and the shared editor tab, never a real profile")
    void testModeBanksOnlyIntoTestProfile() {
        assertTrue(AdvancementProfiles.persists(true, true, PerksGate.TEST_PROFILE));
        assertTrue(AdvancementProfiles.persists(true, true, GlobalAchievementStore.SHARED));
        assertFalse(AdvancementProfiles.persists(true, true, NORMAL), "test earns must never reach Normal");
        assertFalse(AdvancementProfiles.persists(true, true, "hard"));
    }

    @Test
    @DisplayName("Test plus Free Play banks nothing, as Free Play")
    void testPlusFreePlayBanksNothing() {
        assertFalse(AdvancementProfiles.persists(false, true, PerksGate.TEST_PROFILE));
        assertFalse(AdvancementProfiles.persists(false, true, NORMAL));
    }

    @Test
    @DisplayName("live play and ordinary Free Play are unchanged")
    void liveAndFreePlayUnchanged() {
        assertTrue(AdvancementProfiles.persists(true, false, NORMAL));
        assertTrue(AdvancementProfiles.persists(true, false, GlobalAchievementStore.SHARED));
        assertFalse(AdvancementProfiles.persists(false, false, NORMAL));
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
