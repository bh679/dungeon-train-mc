package games.brennan.dungeontrain.ship.sable;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keep/drop rule for a dead rider's server-side carriage pin. The pose maths runs through
 * Sable's {@code Pose3d}, which is compile-only here, so it is covered by the in-game harness.
 */
class DeadRiderServerCarryTest {

    @Test
    void deadAnchorOnLiveCarriageKeepsRiding() {
        assertTrue(DeadRiderServerCarry.shouldKeep(true, false));
    }

    @Test
    void carriageGoneDropsThePin() {
        // A reloaded carriage is a new wrapper instance: the body stops where it is, like the client.
        assertFalse(DeadRiderServerCarry.shouldKeep(true, true));
    }

    @Test
    void respawnedOrLeftPlayerDropsThePin() {
        assertFalse(DeadRiderServerCarry.shouldKeep(false, false));
        assertFalse(DeadRiderServerCarry.shouldKeep(false, true));
    }
}
