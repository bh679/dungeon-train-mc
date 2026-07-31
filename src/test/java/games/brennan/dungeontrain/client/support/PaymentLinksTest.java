package games.brennan.dungeontrain.client.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the donation-route decision: who is offered the China payment link, and how
 * the Revolut note suffix is built. The Minecraft-facing accessors are deliberately not covered —
 * everything they feed is exercised here through the package-private overloads.
 */
class PaymentLinksTest {

    private static final String CN = "https://buy.stripe.com/test_abc123";

    // --- the locale/URL matrix ---------------------------------------------------------------

    @Test
    void chineseClientWithConfiguredLinkTakesTheChinaRoute() {
        assertTrue(PaymentLinks.useChinaPayment("zh_cn", CN));
        assertTrue(PaymentLinks.useChinaPayment("zh_tw", CN));
    }

    @Test
    void chineseClientWithoutAConfiguredLinkKeepsPatreon() {
        // The relay has not served payment_cn — behaviour must be exactly as before the feature.
        assertFalse(PaymentLinks.useChinaPayment("zh_cn", null));
    }

    @Test
    void nonChineseClientKeepsPatreonEvenWhenConfigured() {
        assertFalse(PaymentLinks.useChinaPayment("en_us", CN));
        assertFalse(PaymentLinks.useChinaPayment("ja_jp", CN));
        assertFalse(PaymentLinks.useChinaPayment("ko_kr", CN));
    }

    @Test
    void headlessOrUnknownLocaleKeepsPatreon() {
        assertFalse(PaymentLinks.useChinaPayment(null, CN));
    }

    // --- locale classification ---------------------------------------------------------------

    @Test
    void chineseFamilyCoversSimplifiedTraditionalAndClassical() {
        assertTrue(PaymentLinks.isChineseLocale("zh_cn"));
        assertTrue(PaymentLinks.isChineseLocale("zh_tw"));
        assertTrue(PaymentLinks.isChineseLocale("lzh"));
    }

    @Test
    void nonChineseLocalesAreNotChinese() {
        assertFalse(PaymentLinks.isChineseLocale("en_us"));
        assertFalse(PaymentLinks.isChineseLocale("ja_jp"));
        assertFalse(PaymentLinks.isChineseLocale(""));
        assertFalse(PaymentLinks.isChineseLocale(null));
    }

    // --- note suffix -------------------------------------------------------------------------

    @Test
    void playerNameIsAppendedToANoteCarryingUrl() {
        assertEquals("https://revolut.me/x?note=DT%20Steve",
                PaymentLinks.withPlayerNote("https://revolut.me/x?note=DT%20", "Steve"));
    }

    @Test
    void spacesInPlayerNamesEncodeAsPercent20NotPlus() {
        assertEquals("https://revolut.me/x?note=A%20B",
                PaymentLinks.withPlayerNote("https://revolut.me/x?note=", "A B"));
    }

    @Test
    void aUrlWithoutANoteFieldIsUsedVerbatim() {
        // The Stripe link has no note field, so it must not pick up a name suffix.
        assertEquals(CN, PaymentLinks.withPlayerNote(CN, "Steve"));
    }

    @Test
    void nullBaseSurvives() {
        assertNull(PaymentLinks.withPlayerNote(null, "Steve"));
    }

    // --- client_reference_id (the Stripe counterpart of the Revolut note) ---------------------

    @Test
    void playerNameRidesAlongAsClientReferenceId() {
        assertEquals(CN + "?client_reference_id=Steve",
                PaymentLinks.withClientReference(CN, "Steve"));
    }

    @Test
    void anExistingQueryStringIsExtendedNotRestarted() {
        assertEquals("https://donate.stripe.com/abc?locale=zh&client_reference_id=Steve",
                PaymentLinks.withClientReference("https://donate.stripe.com/abc?locale=zh", "Steve"));
    }

    @Test
    void charactersStripeWouldRejectAreStripped() {
        // Stripe silently drops the whole value if it contains anything outside [A-Za-z0-9_-],
        // so the name is cleaned rather than trusted.
        assertEquals(CN + "?client_reference_id=Steve_1-2",
                PaymentLinks.withClientReference(CN, "Steve_1-2"));
        assertEquals(CN + "?client_reference_id=Steve", PaymentLinks.withClientReference(CN, "Ste ve!"));
        assertEquals(CN + "?client_reference_id=abc", PaymentLinks.withClientReference(CN, "@abc#"));
    }

    @Test
    void aNameWithNothingUsableLeavesTheParameterOff() {
        // Better no parameter than one Stripe discards.
        assertEquals(CN, PaymentLinks.withClientReference(CN, "玩家"));
        assertEquals(CN, PaymentLinks.withClientReference(CN, ""));
        assertEquals(CN, PaymentLinks.withClientReference(CN, null));
    }

    @Test
    void overlongNamesAreTruncatedToStripesCap() {
        String ref = PaymentLinks.sanitizeClientReference("a".repeat(500));
        assertEquals(200, ref.length());
    }

    @Test
    void nullBaseSurvivesClientReference() {
        assertNull(PaymentLinks.withClientReference(null, "Steve"));
    }
}
