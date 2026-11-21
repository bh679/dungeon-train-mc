package games.brennan.dungeontrain.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerkUnlockAfterDeathTest {

    @Test
    void unlockingWaitsForDeath() {
        assertFalse(PerkUnlockPacket.afterDeath(false, false), "alive in play: no unlocking");
        assertTrue(PerkUnlockPacket.afterDeath(true, false), "dead on the death menu");
        assertTrue(PerkUnlockPacket.afterDeath(false, true), "respawned into the tribute room, menu on the TV");
    }
}
