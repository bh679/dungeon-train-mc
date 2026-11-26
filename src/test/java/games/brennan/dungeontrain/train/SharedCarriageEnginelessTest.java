package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether stepping aboard a drifting carriage is announced: not once every engine has been carried off,
 * which used to keep greeting players with the fresh-carriage line.
 */
class SharedCarriageEnginelessTest {

    @Test
    void aCarriageWithItsEnginesCarriedOffIsNotAnnounced() {
        assertFalse(SharedCarriageMessage.announces(0, 0));
    }

    @Test
    void aCarriageWithAnEngineIsAnnouncedFedOrNot() {
        assertTrue(SharedCarriageMessage.announces(1, 0), "a fresh carriage, engine waiting for coal");
        assertTrue(SharedCarriageMessage.announces(2, 5));
    }

    @Test
    void anOldBuildWithNoEngineButFuelLeftKeepsItsMessage() {
        assertTrue(SharedCarriageMessage.announces(0, 2));
    }
}
