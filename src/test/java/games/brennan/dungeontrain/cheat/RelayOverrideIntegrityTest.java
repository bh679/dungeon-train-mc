package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A release build pointed at another relay is Free Play; a dev build, or no override, is not. */
final class RelayOverrideIntegrityTest {

    @Test
    @DisplayName("either relay address variable taints a release build")
    void overrideTaintsRelease() {
        assertTrue(RelayOverrideIntegrity.taints(false, Map.of("DUNGEONTRAIN_RELAY_BASE_URL", "http://127.0.0.1:3917/x")::get));
        assertTrue(RelayOverrideIntegrity.taints(false, Map.of("DUNGEONTRAIN_RELAY_LIVE_BASE_URL", "http://evil")::get));
    }

    @Test
    @DisplayName("no override, a blank one, or a dev build does not")
    void noTaint() {
        assertFalse(RelayOverrideIntegrity.taints(false, Map.<String, String>of()::get));
        assertFalse(RelayOverrideIntegrity.taints(false, Map.of("DUNGEONTRAIN_RELAY_BASE_URL", "  ")::get));
        assertFalse(RelayOverrideIntegrity.taints(true, Map.of("DUNGEONTRAIN_RELAY_BASE_URL", "http://127.0.0.1")::get));
    }
}
