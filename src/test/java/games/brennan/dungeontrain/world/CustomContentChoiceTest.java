package games.brennan.dungeontrain.world;

import games.brennan.dungeontrain.config.CustomContentPreference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Free Play is off by default: a world nobody has answered for plays the bundled content, and only
 * an explicit yes loads the player's own designs.
 */
class CustomContentChoiceTest {

    @Test
    @DisplayName("An unanswered world suppresses custom content, the same as DISABLE")
    void unsetSuppresses() {
        assertTrue(CustomContentChoice.UNSET.suppressesContent());
        assertTrue(CustomContentChoice.DISABLE.suppressesContent());
        assertFalse(CustomContentChoice.UNSET.isAnswered());
    }

    @Test
    @DisplayName("Only ALLOW and DEV_IGNORE load the custom content")
    void onlyAnExplicitYesLoads() {
        assertFalse(CustomContentChoice.ALLOW.suppressesContent());
        assertFalse(CustomContentChoice.DEV_IGNORE.suppressesContent());
    }

    @Test
    @DisplayName("The remembered DISABLE preference answers worlds with DISABLE")
    void disablePreferenceMapsToDisable() {
        assertEquals(CustomContentChoice.DISABLE, CustomContentPreference.DISABLE.toChoice());
        assertFalse(CustomContentPreference.DISABLE.asks());
    }
}
