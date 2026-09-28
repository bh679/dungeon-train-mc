package games.brennan.dungeontrain.client.portal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arrival window, which used to be a one-shot claim — and the bug that made it worth changing:
 * the first frame to ask is not necessarily the first frame of the arrival, so a token spent by
 * reading it could be spent on a frame that had nothing to do, leaving the real arrival uncovered.
 */
class ClientPortalSwapTest {

    @AfterEach
    void clear() {
        ClientPortalSwap.reset();
    }

    @Test
    @DisplayName("nothing is an arrival until a swap arms one")
    void closedUntilArmed() {
        assertFalse(ClientPortalSwap.inArrivalWindow());
    }

    @Test
    @DisplayName("the window survives being read — every frame of an arrival gets the same answer")
    void readingDoesNotConsume() {
        ClientPortalSwap.arm();
        for (int frame = 0; frame < 100; frame++) {
            assertTrue(ClientPortalSwap.inArrivalWindow(), "frame " + frame);
        }
    }

    @Test
    @DisplayName("the nearby-compile pass is still one frame's worth, not the whole window")
    void nearbyCompileIsStillOnePass() {
        ClientPortalSwap.arm();
        assertTrue(ClientPortalSwap.wantsNearbyCompile());

        // Vanilla asks once per dirty section within a pass, so it stays true across the pass...
        assertTrue(ClientPortalSwap.wantsNearbyCompile());

        // ...and the end of the pass is what closes it, even though the window is still open.
        ClientPortalSwap.finishNearbyCompile();
        assertFalse(ClientPortalSwap.wantsNearbyCompile());
        assertTrue(ClientPortalSwap.inArrivalWindow());
    }

    @Test
    @DisplayName("resetting closes the window outright")
    void resetCloses() {
        ClientPortalSwap.arm();
        assertTrue(ClientPortalSwap.inArrivalWindow());

        ClientPortalSwap.reset();
        assertFalse(ClientPortalSwap.inArrivalWindow());
        assertFalse(ClientPortalSwap.wantsNearbyCompile());
    }
}
