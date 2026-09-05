package games.brennan.dungeontrain.ship.sable;

import games.brennan.dungeontrain.ship.KinematicDriver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The driver → rotation-lock rule. Pure, so it runs without a Minecraft/Sable bootstrap; the two
 * mixins that consume the flag are verified in-game.
 */
class TrainRotationLockTest {

    /** Stand-in for a non-train driver — anything that is not a TrainTransformProvider. */
    private static final KinematicDriver OTHER_DRIVER = input -> null;

    @Test
    @DisplayName("A sub-level with no driver is not rotation-locked")
    void noDriverDoesNotLock() {
        assertFalse(TrainRotationLock.locksFor(null));
    }

    @Test
    @DisplayName("A driver that isn't a train driver leaves Sable's normal physics alone")
    void foreignDriverDoesNotLock() {
        assertFalse(TrainRotationLock.locksFor(OTHER_DRIVER));
    }

    @Test
    @DisplayName("isLocked ignores anything that isn't a sub-level")
    void nonSubLevelIsNotLocked() {
        assertFalse(TrainRotationLock.isLocked(null));
        assertFalse(TrainRotationLock.isLocked("not a sub-level"));
    }
}
