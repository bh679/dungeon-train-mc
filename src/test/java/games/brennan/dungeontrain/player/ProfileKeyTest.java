package games.brennan.dungeontrain.player;

import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.perks.PerksGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test mode is a profile of its own, and off the record. */
final class ProfileKeyTest {

    @AfterEach
    void reset() {
        PerksGate.setTestWorldForTests(false);
    }

    @Test
    @DisplayName("a test world's profile is the test profile, whatever the difficulty")
    void testWorldProfile() {
        assertEquals(PerksGate.TEST_PROFILE, ProfileKey.of(true, "hard"));
        assertEquals("hard", ProfileKey.of(false, "hard"));
    }

    @Test
    @DisplayName("a test world is isolated, off the record, and sends nothing to the relay")
    void testWorldOffRecord() {
        assertFalse(RunIntegrity.isOffRecordWorld());
        PerksGate.setTestWorldForTests(true);
        assertTrue(ProfileKey.isolated());
        assertTrue(RunIntegrity.isOffRecordWorld());
        assertFalse(DungeonTrainConfig.isWorldInfoToRelay());
    }
}
