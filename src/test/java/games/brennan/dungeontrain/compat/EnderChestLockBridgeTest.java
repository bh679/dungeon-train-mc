package games.brennan.dungeontrain.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate that keeps a live Ender Chest swap out of the window where EnderChestPersistence would have
 * to guess the outgoing slot — the path that saved an empty chest over a player's survival stash.
 */
class EnderChestLockBridgeTest {

    @Test
    @DisplayName("A player ECP has restored can be swapped")
    void settledSwaps() {
        assertTrue(EnderChestLockBridge.shouldEngage(true, true));
    }

    @Test
    @DisplayName("Joining (not restored yet) or leaving: never swapped")
    void unsettledNeverSwaps() {
        assertFalse(EnderChestLockBridge.shouldEngage(true, false));
    }

    @Test
    @DisplayName("Without the ECP lock installed nothing is swapped")
    void notInstalled() {
        assertFalse(EnderChestLockBridge.shouldEngage(false, true));
        assertFalse(EnderChestLockBridge.shouldEngage(false, false));
    }
}
