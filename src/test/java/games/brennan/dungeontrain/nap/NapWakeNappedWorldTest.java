package games.brennan.dungeontrain.nap;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the match behind refusing logins to a world its owner napped in. */
class NapWakeNappedWorldTest {

    private static final String HERE = "Dungeon Train 1791666517688";

    @Test void pendingNapForThisWorldMatches() {
        assertTrue(NapWake.napNames(HERE, List.of("World 1791741323610", HERE)));
    }

    @Test void napForAnotherWorldDoesNotMatch() {
        assertFalse(NapWake.napNames(HERE, List.of("World 1791741323610")));
    }

    @Test void noPendingNapsDoNotMatch() {
        assertFalse(NapWake.napNames(HERE, List.of()));
    }

    @Test void unknownWorldDoesNotMatch() {
        assertFalse(NapWake.napNames(null, List.of(HERE)));
    }
}
