package games.brennan.dungeontrain.client.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who a donation is on behalf of: the player by default, a typed name only when it is valid. */
class DonationRecipientTest {

    @AfterEach
    void clear() {
        DonationRecipient.reset();
    }

    @Test
    void minecraftNameRule() {
        assertTrue(DonationRecipient.isValidName("Steve"));
        assertTrue(DonationRecipient.isValidName("abc"));
        assertTrue(DonationRecipient.isValidName("a_b_C_123456789X")); // 16
        assertFalse(DonationRecipient.isValidName("ab"));
        assertFalse(DonationRecipient.isValidName("a_b_C_123456789XY")); // 17
        assertFalse(DonationRecipient.isValidName("Bad Name"));
        assertFalse(DonationRecipient.isValidName("名字名字"));
        assertFalse(DonationRecipient.isValidName(""));
        assertFalse(DonationRecipient.isValidName(null));
    }

    @Test
    void defaultsToTheSelfName() {
        assertEquals("Brennan", DonationRecipient.nameFor("Brennan"));
        assertFalse(DonationRecipient.isGifting());
        assertFalse(DonationRecipient.blocked());
    }

    @Test
    void someoneElseWithAValidNameCarriesThatName() {
        DonationRecipient.chooseOther();
        DonationRecipient.setTyped("  Steve ");
        assertEquals("Steve", DonationRecipient.nameFor("Brennan"));
        assertTrue(DonationRecipient.isGifting());
        assertFalse(DonationRecipient.blocked());
    }

    @Test
    void invalidTypedNameIsBlockedAndNeverSent() {
        DonationRecipient.chooseOther();
        DonationRecipient.setTyped("St");
        assertTrue(DonationRecipient.blocked());
        assertFalse(DonationRecipient.isGifting());
        assertEquals("Brennan", DonationRecipient.nameFor("Brennan"));
    }

    @Test
    void switchingBackToSelfKeepsTheTypedNameButSendsSelf() {
        DonationRecipient.chooseOther();
        DonationRecipient.setTyped("Steve");
        DonationRecipient.chooseSelf();
        assertEquals("Brennan", DonationRecipient.nameFor("Brennan"));
        assertEquals("Steve", DonationRecipient.typed());
    }

    @Test
    void resetReturnsToSelfAndClearsTheName() {
        DonationRecipient.chooseOther();
        DonationRecipient.setTyped("Steve");
        DonationRecipient.reset();
        assertFalse(DonationRecipient.isOther());
        assertEquals("", DonationRecipient.typed());
        assertEquals("Brennan", DonationRecipient.nameFor("Brennan"));
    }
}
