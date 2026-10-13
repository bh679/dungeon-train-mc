package games.brennan.dungeontrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.brennan.dungeontrain.config.ContentMode;
import org.junit.jupiter.api.Test;

/**
 * Where the consent card's Livestreaming / Developer chat switches start ({@link DungeonTrain#switchStart}).
 * Reopened from Options on the player's current mode, a switch shows what they have now; on the other
 * mode, or on a first launch / re-ask, it shows that mode's default.
 */
class ConsentSwitchStartTest {

    @Test
    void reopenOnCurrentModeKeepsStoredValue() {
        // An adult who turned Livestreaming off sees it still off — the default (on) does not win.
        assertFalse(DungeonTrain.switchStart(true, ContentMode.ADULT, ContentMode.ADULT, false, true));
        assertTrue(DungeonTrain.switchStart(true, ContentMode.KID, ContentMode.KID, true, false));
    }

    @Test
    void reopenOnOtherModeTakesThatModesDefault() {
        assertFalse(DungeonTrain.switchStart(true, ContentMode.KID, ContentMode.ADULT, true, false));
        assertTrue(DungeonTrain.switchStart(true, ContentMode.ADULT, ContentMode.KID, false, true));
    }

    @Test
    void firstLaunchOrReaskTakesModeDefault() {
        assertTrue(DungeonTrain.switchStart(false, ContentMode.ADULT, ContentMode.ADULT, false, true));
        assertFalse(DungeonTrain.switchStart(false, ContentMode.KID, ContentMode.KID, true, false));
    }
}
