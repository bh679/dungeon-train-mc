package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link LoyaltyTridentRecall#shouldRecall}: a Loyalty trident goes straight back only
 * once vanilla can't bring it home itself, and never to a dead or spectating owner.
 */
class LoyaltyTridentRecallTest {

    @Test
    @DisplayName("a ticking trident near its owner is left to vanilla's return flight")
    void tickingSameDimensionStays() {
        assertFalse(LoyaltyTridentRecall.shouldRecall(true, false, true, true, false));
    }

    @Test
    @DisplayName("left behind by the train, in another dimension, or in the void: it comes back")
    void lostCasesRecall() {
        assertTrue(LoyaltyTridentRecall.shouldRecall(true, false, true, false, false), "chunk stopped ticking");
        assertTrue(LoyaltyTridentRecall.shouldRecall(true, false, false, true, false), "owner in another dimension");
        assertTrue(LoyaltyTridentRecall.shouldRecall(true, false, true, true, true), "below the world");
    }

    @Test
    @DisplayName("a dead or spectating owner gets nothing")
    void deadOrSpectatorNever() {
        assertFalse(LoyaltyTridentRecall.shouldRecall(false, false, true, false, false), "dead");
        assertFalse(LoyaltyTridentRecall.shouldRecall(true, true, false, false, true), "spectator");
    }
}
