package games.brennan.dungeontrain.perks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PerksGateTest {

    @AfterEach
    void reset() {
        PerksGate.clearClient();
        PendingPerksTest.take();
    }

    @Test
    @DisplayName("perks are not launched: off unless the world is a perks test world")
    void offByDefault() {
        assertFalse(PerksGate.LAUNCHED, "flip only on launch day, with held.py launch perks");
        assertFalse(PerksGate.isOn());
        assertFalse(PerksGate.isTestWorld());
        assertFalse(PerksGate.isOnClient());
        assertFalse(PerksGate.isOn(null));
    }

    @Test
    @DisplayName("the client follows what the server says, and forgets it on leaving")
    void clientMirror() {
        PerksGate.setClient(true);
        assertTrue(PerksGate.isOnClient());
        PerksGate.clearClient();
        assertFalse(PerksGate.isOnClient());
    }

    @Test
    @DisplayName("a pending perks test world is taken once")
    void pendingTakenOnce() {
        PendingPerksTest.set(true);
        assertTrue(PendingPerksTest.take());
        assertFalse(PendingPerksTest.take());
    }
}
