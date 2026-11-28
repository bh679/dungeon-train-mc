package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.narrative.StartingBookVoidToss.countsAsVoidToss;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for when a gone starting book counts as tossed into the void. No Minecraft bootstrap. */
class StartingBookVoidTossTest {

    @Test
    void burntOutMidFallOverAnEmptyColumnCounts() {
        assertTrue(countsAsVoidToss(false, 40.0, true, false));
    }

    @Test
    void fallingOutOfTheWorldAlwaysCounts() {
        assertTrue(countsAsVoidToss(false, 300.0, true, true));
    }

    @Test
    void burningOnTheDeckOverTheVoidDoesNotCount() {
        // The deck is a Sable shipyard block: the world column under it is empty, but the book never fell.
        assertFalse(countsAsVoidToss(true, 1.0, true, false));
        assertFalse(countsAsVoidToss(false, 1.0, true, false));
    }

    @Test
    void fallingOverLandDoesNotCount() {
        assertFalse(countsAsVoidToss(false, 40.0, false, false));
    }

    @Test
    void landedDoesNotCount() {
        assertFalse(countsAsVoidToss(true, 40.0, true, false));
    }
}
