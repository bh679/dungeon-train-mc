package games.brennan.dungeontrain.train;

import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins when a carriage stamp reads as a Test-the-Carriage copy — the one question that decides
 * whether its hostiles spawn as authored or go through the onboarding ramp.
 */
final class CarriageTestSessionTest {

    private static CarriageTestSession.Session session() {
        return new CarriageTestSession.Session(Level.OVERWORLD, Vec3.ZERO, 0f, 0f, GameType.SURVIVAL,
            CarriageTestSession.Kind.CARRIAGE, "pen", new BoundingBox(0, 0, 0, 8, 6, 4), 1L, 1L);
    }

    @AfterEach
    void clear() {
        CarriageTestSession.clear();
    }

    @Test
    @DisplayName("The test index is a test stamp only while a test is running")
    void isTestStamp_needsLiveSession() {
        assertFalse(CarriageTestSession.isTestStamp(CarriageTestSession.TEST_INDEX));
        CarriageTestSession.put(UUID.randomUUID(), session());
        assertTrue(CarriageTestSession.isTestStamp(CarriageTestSession.TEST_INDEX));
        // Any other index is a real carriage on the train, test or no test.
        assertFalse(CarriageTestSession.isTestStamp(CarriageTestSession.TEST_INDEX + 1));
    }

    @Test
    @DisplayName("Back takes the session, so a second Back finds nothing")
    void take_isOneShot() {
        UUID player = UUID.randomUUID();
        CarriageTestSession.put(player, session());
        assertTrue(CarriageTestSession.has(player));
        CarriageTestSession.take(player);
        assertNull(CarriageTestSession.take(player));
        assertFalse(CarriageTestSession.anyActive());
    }

    @Test
    @DisplayName("Kind literals are the editor category ids the client sends")
    void kindLiterals() {
        assertTrue("carriages".equals(CarriageTestSession.Kind.CARRIAGE.literal()));
        assertTrue("contents".equals(CarriageTestSession.Kind.CONTENTS.literal()));
    }
}
