package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure classification for {@link CheatModIntegrity#detectedFrom(Set, Map)} — which installed mods
 * are cheat mods, as {@code "<modId> v<version>"} display strings. No live {@code ModList} needed.
 */
class CheatModIntegrityTest {

    private static final Set<String> CHEATS = Set.of("xray", "freecam", "baritone");

    @Test
    @DisplayName("A listed cheat mod is detected with its version")
    void detectsListedMod() {
        Map<String, String> installed = new LinkedHashMap<>();
        installed.put("minecraft", "1.21.1");
        installed.put("xray", "1.2.3");
        installed.put("dungeontrain", "0.506.0");

        assertEquals(List.of("xray v1.2.3"), CheatModIntegrity.detectedFrom(CHEATS, installed));
    }

    @Test
    @DisplayName("Matching is case-insensitive on the mod ID")
    void caseInsensitive() {
        Map<String, String> installed = Map.of("XRay", "9");
        assertEquals(List.of("XRay v9"), CheatModIntegrity.detectedFrom(CHEATS, installed));
    }

    @Test
    @DisplayName("A clean mod list yields no detections")
    void cleanIsEmpty() {
        Map<String, String> installed = new LinkedHashMap<>();
        installed.put("minecraft", "1.21.1");
        installed.put("dungeontrain", "0.506.0");
        installed.put("sable", "2.0.2");

        assertTrue(CheatModIntegrity.detectedFrom(CHEATS, installed).isEmpty());
    }

    @Test
    @DisplayName("Unknown mods are ignored; multiple hits are sorted")
    void multipleHitsSorted() {
        Map<String, String> installed = new LinkedHashMap<>();
        installed.put("xray", "1");
        installed.put("someinnocentmod", "4");
        installed.put("baritone", "2");

        assertEquals(List.of("baritone v2", "xray v1"),
            CheatModIntegrity.detectedFrom(CHEATS, installed));
    }

    @Test
    @DisplayName("An empty cheat set never detects anything")
    void emptyCheatSet() {
        Map<String, String> installed = Map.of("xray", "1");
        assertTrue(CheatModIntegrity.detectedFrom(Set.of(), installed).isEmpty());
    }
}
