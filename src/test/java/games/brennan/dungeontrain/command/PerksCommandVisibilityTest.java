package games.brennan.dungeontrain.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerksCommandVisibilityTest {

    @Test
    void alwaysParses() {
        assertTrue(PerksCommandVisibility.shown(false, false));
        assertTrue(PerksCommandVisibility.shown(false, true));
    }

    @Test
    void suggestedOnlyInFreePlay() {
        assertFalse(PerksCommandVisibility.shown(true, false));
        assertTrue(PerksCommandVisibility.shown(true, true));
    }

    @Test
    void treeFlagFollowsBeginAndEnd() {
        PerksCommandVisibility.beginTree();
        assertTrue(PerksCommandVisibility.buildingTree());
        PerksCommandVisibility.endTree();
        assertFalse(PerksCommandVisibility.buildingTree());
    }
}
