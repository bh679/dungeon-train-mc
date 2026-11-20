package games.brennan.dungeontrain.client.credits;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicNameSwitchTest {

    @Test
    @DisplayName("Name shows only for an Adult-mode player who has not hidden it")
    void shown_onlyWhenAdultAndNotHidden() {
        assertTrue(PublicNameSwitch.isShown(false, false));
        assertFalse(PublicNameSwitch.isShown(false, true));
        assertFalse(PublicNameSwitch.isShown(true, false), "Kid mode always hides the name");
        assertFalse(PublicNameSwitch.isShown(true, true));
    }
}
