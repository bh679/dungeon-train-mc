package games.brennan.dungeontrain.portal;

import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a pair key means "a Test-the-Carriage stamp".
 *
 * <p>{@link PortalTestSession#PAIR_KEY} is a legal index on purpose, and the dev-creative portal
 * cadence can stand a real pair on it — one the player rides to, where the onboarding ramp should
 * apply. So the key alone must not read as a test; only the key while a session is live does.</p>
 */
class PortalTestSessionStampTest {

    private static PortalTestSession.Session session() {
        return new PortalTestSession.Session(null, null, 0f, 0f, GameType.CREATIVE, null, "room", null);
    }

    @AfterEach
    void clearSessions() {
        PortalTestSession.clear();
    }

    @Test
    @DisplayName("the test key is not a test stamp while nobody is testing")
    void keyAloneIsNotATest() {
        assertFalse(PortalTestSession.isTestStamp(PortalTestSession.PAIR_KEY));
    }

    @Test
    @DisplayName("the test key is a test stamp while a session is live")
    void keyWithSessionIsATest() {
        PortalTestSession.put(UUID.randomUUID(), session());
        assertTrue(PortalTestSession.isTestStamp(PortalTestSession.PAIR_KEY));
    }

    @Test
    @DisplayName("a real pair's key is never a test stamp, even while someone is testing")
    void otherKeysAreNeverATest() {
        PortalTestSession.put(UUID.randomUUID(), session());
        assertFalse(PortalTestSession.isTestStamp(PortalTestSession.PAIR_KEY + 1));
        assertFalse(PortalTestSession.isTestStamp(-7));
    }

    @Test
    @DisplayName("taking the last session back ends the test")
    void takingTheSessionEndsTheTest() {
        UUID player = UUID.randomUUID();
        PortalTestSession.put(player, session());
        PortalTestSession.take(player);
        assertFalse(PortalTestSession.isTestStamp(PortalTestSession.PAIR_KEY));
    }
}
