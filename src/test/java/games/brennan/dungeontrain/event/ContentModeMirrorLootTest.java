package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Kid with Livestreaming off never gets the live headpiece from loot; everyone else may. */
final class ContentModeMirrorLootTest {

    @Test
    @DisplayName("only Kid + Livestreaming off is refused the headpiece")
    void mayLoot() {
        assertTrue(ContentModeMirror.mayLoot(false, true));
        assertTrue(ContentModeMirror.mayLoot(false, false));
        assertTrue(ContentModeMirror.mayLoot(true, true));
        assertFalse(ContentModeMirror.mayLoot(true, false));
    }
}
