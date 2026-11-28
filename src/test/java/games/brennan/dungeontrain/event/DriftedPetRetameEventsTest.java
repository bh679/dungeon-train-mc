package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.event.DriftedPetRetameEvents.mayRetame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for who may retame a pet that drifted in on a carriage. No Minecraft bootstrap. */
class DriftedPetRetameEventsTest {

    @Test
    void anyoneMayRetameADriftedPetWhoseOwnerNeverPlaysHere() {
        assertTrue(mayRetame(true, false, true, false, true));
    }

    @Test
    void anOwnerInTheWorldKeepsTheirPet() {
        // Online, saved here while offline, or the singleplayer host: all count as in the world.
        assertFalse(mayRetame(true, false, true, true, true));
    }

    @Test
    void theOwnersOwnClickIsVanilla() {
        assertFalse(mayRetame(true, true, true, false, true));
    }

    @Test
    void aWildAnimalIsVanilla() {
        assertFalse(mayRetame(false, false, true, false, true));
    }

    @Test
    void petsThatDidNotComeFromACarriageAreLeftAlone() {
        assertFalse(mayRetame(true, false, false, false, true));
    }

    @Test
    void onlyTheTamingItemRetames() {
        assertFalse(mayRetame(true, false, true, false, false));
    }
}
