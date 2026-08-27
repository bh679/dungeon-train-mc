package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure classification for {@link OperatorIntegrity#detectedFrom(Map)} — which online players have
 * cheats — plus the session-flag contract the ~20 Free Play persistence gates read through
 * {@link RunIntegrity#isCheated}. No live server needed.
 */
class OperatorIntegrityTest {

    @AfterEach
    void reset() {
        OperatorIntegrity.setDetectedForTest(null);
    }

    @Test
    @DisplayName("Permission level 2 and above counts as having cheats")
    void opLevelsDetected() {
        Map<String, Integer> levels = new LinkedHashMap<>();
        levels.put("moderator", 2);
        levels.put("admin", 3);
        levels.put("singleplayer_host", 4);

        assertEquals(List.of("admin", "moderator", "singleplayer_host"),
            OperatorIntegrity.detectedFrom(levels));
    }

    @Test
    @DisplayName("Ordinary players — level 0 and the level-1 bypass tier — are not operators")
    void nonOpsIgnored() {
        Map<String, Integer> levels = new LinkedHashMap<>();
        levels.put("player", 0);
        levels.put("bypasses_spawn_protection", 1);

        assertTrue(OperatorIntegrity.detectedFrom(levels).isEmpty());
    }

    @Test
    @DisplayName("One operator among ordinary players is still detected, and names are sorted")
    void oneOpAmongPlayers() {
        Map<String, Integer> levels = new LinkedHashMap<>();
        levels.put("zoe", 0);
        levels.put("host", 4);
        levels.put("alex", 0);

        assertEquals(List.of("host"), OperatorIntegrity.detectedFrom(levels));
    }

    @Test
    @DisplayName("An empty server has no operators")
    void emptyServer() {
        assertTrue(OperatorIntegrity.detectedFrom(Map.of()).isEmpty());
    }

    @Test
    @DisplayName("Null names and null levels are skipped rather than throwing")
    void nullsSkipped() {
        Map<String, Integer> levels = new LinkedHashMap<>();
        levels.put("ghost", null);
        levels.put("host", 4);

        assertEquals(List.of("host"), OperatorIntegrity.detectedFrom(levels));
    }

    @Test
    @DisplayName("The session flag follows the detected snapshot")
    void sessionFlagFollowsSnapshot() {
        assertFalse(OperatorIntegrity.isSessionFreePlay());

        OperatorIntegrity.setDetectedForTest(List.of("host"));
        assertTrue(OperatorIntegrity.isSessionFreePlay());
        assertEquals(List.of("host"), OperatorIntegrity.detected());

        // The last operator leaving lifts the session-wide taint — runs already stamped with the
        // RUN_CHEATED attachment stay Free Play, which is what stops /op → cheat → /deop.
        OperatorIntegrity.setDetectedForTest(List.of());
        assertFalse(OperatorIntegrity.isSessionFreePlay());
    }
}
