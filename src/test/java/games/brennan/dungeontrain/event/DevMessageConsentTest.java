package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DevMessageConsent#isValid(boolean, double, double, long, long)} — the pure
 * consent-lifetime rule: consent is valid while EITHER granted in the current world session OR the
 * last message to the dev was within the 20-minute window, and expires only once BOTH a new world
 * has loaded AND the window has lapsed ("whichever comes last").
 */
final class DevMessageConsentTest {

    private static final long WINDOW = DevMessageConsent.CONSENT_WINDOW_MS; // 20 minutes
    private static final double SESSION_A = 1_000.0;
    private static final double SESSION_B = 2_000.0;
    private static final long T0 = 10_000_000L;

    @Test
    @DisplayName("never valid before consent is granted")
    void notGranted() {
        assertFalse(DevMessageConsent.isValid(false, SESSION_A, SESSION_A, T0, T0));
    }

    @Test
    @DisplayName("valid indefinitely within the world session it was granted in")
    void sameSessionAlwaysValid() {
        // Long past the window, but still the grant session → valid.
        assertTrue(DevMessageConsent.isValid(true, SESSION_A, SESSION_A, T0, T0 + 10 * WINDOW));
    }

    @Test
    @DisplayName("survives a new world while the 20-minute window is still open")
    void newWorldButRecent() {
        long lastMsg = T0;
        long now = T0 + WINDOW - 1; // still inside the window
        assertTrue(DevMessageConsent.isValid(true, SESSION_A, SESSION_B, lastMsg, now));
    }

    @Test
    @DisplayName("expires once a new world has loaded AND the window has lapsed")
    void newWorldAndStale() {
        long lastMsg = T0;
        long now = T0 + WINDOW; // window has exactly lapsed (strict <)
        assertFalse(DevMessageConsent.isValid(true, SESSION_A, SESSION_B, lastMsg, now));
    }

    @Test
    @DisplayName("a fresh (unset) server session token never counts as a same-session match")
    void zeroSessionIsNotSameSession() {
        // sessionId 0.0 means "no world yet"; falls through to the window, which is stale here.
        assertFalse(DevMessageConsent.isValid(true, 0.0, 0.0, T0, T0 + WINDOW));
        // ...but a recent message still keeps it valid.
        assertTrue(DevMessageConsent.isValid(true, 0.0, 0.0, T0, T0 + WINDOW - 1));
    }
}
