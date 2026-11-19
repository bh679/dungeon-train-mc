package games.brennan.dungeontrain.client.links;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for the official-links overlay: validation, sanitize, fallback resolution. */
class OfficialLinksTest {

    @AfterEach
    void tearDown() {
        OfficialLinks.reset();
    }

    // --- fallback behaviour -----------------------------------------------------------------

    @Test
    void bakedFallbacksServedWithNoOverlay() {
        assertEquals(OfficialLinks.FALLBACK_DISCORD, OfficialLinks.discord());
        assertEquals(OfficialLinks.FALLBACK_PATREON, OfficialLinks.patreon());
        assertEquals(OfficialLinks.FALLBACK_PAYMENT, OfficialLinks.payment());
        assertEquals(OfficialLinks.FALLBACK_AFFILIATE, OfficialLinks.affiliate());
        assertEquals(OfficialLinks.FALLBACK_BILIBILI, OfficialLinks.bilibili());
        assertEquals(OfficialLinks.FALLBACK_YOUTUBE, OfficialLinks.youtube());
        assertEquals(OfficialLinks.FALLBACK_INSTAGRAM, OfficialLinks.instagram());
    }

    @Test
    void channelLinksOverlayFromTheRelay() {
        OfficialLinks.accept(Map.of("youtube", "https://www.youtube.com/@moved", "instagram", "https://instagram.com/moved"));
        assertEquals("https://www.youtube.com/@moved", OfficialLinks.youtube());
        assertEquals("https://instagram.com/moved", OfficialLinks.instagram());
    }

    @Test
    void bilibiliIsBakedSoAChineseClientNeverNeedsTheRelay() {
        // The players offered this link are the ones least likely to have a clean route to the
        // relay, so the channel must resolve with no fetch at all — unlike payment_cn, which is
        // relay-only on purpose.
        assertTrue(OfficialLinks.bilibili().startsWith("https://space.bilibili.com/"));
    }

    @Test
    void aRelayServedBilibiliOverlaysTheBakedChannel() {
        OfficialLinks.accept(Map.of("bilibili", "https://space.bilibili.com/999"));
        assertEquals("https://space.bilibili.com/999", OfficialLinks.bilibili());
    }

    @Test
    void paymentFallbackDefaultsTo25Aud() {
        assertTrue(OfficialLinks.payment().contains("amount=2500"),
                "default direct-donation amount should be $25 AUD (2500 cents)");
    }

    // --- the checkout route behind the named price points ------------------------------------

    @Test
    void noCheckoutRouteServedMeansNoPricePoints() {
        // Relay-only with no baked default: three buttons that lead nowhere would be worse than the
        // open-ended options they replace, so both screens degrade instead.
        assertNull(OfficialLinks.paymentCheckout());
    }

    @Test
    void theGeneralCheckoutKeyWinsOverTheChinaOne() {
        OfficialLinks.accept(Map.of(
                "payment_checkout", "https://brennan.games/api/dp-relay/cap/pay/cn",
                "payment_cn_checkout", "https://brennan.games/api/dp-relay/othercap/pay/cn"));
        assertEquals("https://brennan.games/api/dp-relay/cap/pay/cn", OfficialLinks.paymentCheckout());
    }

    @Test
    void aRelayPredatingTheGeneralKeyStillLightsUpThePricePoints() {
        OfficialLinks.accept(Map.of("payment_cn_checkout", "https://brennan.games/api/dp-relay/cap/pay/cn"));
        assertEquals("https://brennan.games/api/dp-relay/cap/pay/cn", OfficialLinks.paymentCheckout());
    }

    @Test
    void relayOverlayReplacesOnlyServedKeys() {
        OfficialLinks.accept(Map.of("discord", "https://discord.gg/rotated"));
        assertEquals("https://discord.gg/rotated", OfficialLinks.discord());
        assertEquals(OfficialLinks.FALLBACK_PATREON, OfficialLinks.patreon());
        assertEquals(OfficialLinks.FALLBACK_AFFILIATE, OfficialLinks.affiliate());
    }

    @Test
    void invalidRelayValueKeepsFallbackForThatKey() {
        Map<String, String> raw = new HashMap<>();
        raw.put("discord", "javascript:alert(1)");
        raw.put("patreon", "https://www.patreon.com/other");
        OfficialLinks.accept(raw);
        assertEquals(OfficialLinks.FALLBACK_DISCORD, OfficialLinks.discord());
        assertEquals("https://www.patreon.com/other", OfficialLinks.patreon());
    }

    // --- payment_cn: relay-only, no baked fallback ---------------------------------------------

    @Test
    void paymentCnIsNullUntilTheRelayServesIt() {
        assertNull(OfficialLinks.paymentCn(),
                "no baked fallback — a jar built before the link existed must see null");
    }

    @Test
    void paymentCnServedFromRelay() {
        OfficialLinks.accept(Map.of("payment_cn", "https://buy.stripe.com/test_abc123"));
        assertEquals("https://buy.stripe.com/test_abc123", OfficialLinks.paymentCn());
    }

    @Test
    void invalidPaymentCnDegradesToNullNotToTheRevolutLink() {
        Map<String, String> raw = new HashMap<>();
        raw.put("payment_cn", "notaurl");
        OfficialLinks.accept(raw);
        assertNull(OfficialLinks.paymentCn());
        assertEquals(OfficialLinks.FALLBACK_PAYMENT, OfficialLinks.payment(),
                "a bad payment_cn must not disturb the ordinary payment key");
    }

    @Test
    void overlayValuesAreTrimmed() {
        OfficialLinks.accept(Map.of("affiliate", "  https://example.com/aff  "));
        assertEquals("https://example.com/aff", OfficialLinks.affiliate());
    }

    // --- payment host allowlist ----------------------------------------------------------------

    @Test
    void theLinksTheRelayServesTodayAllPass() {
        Map<String, String> live = Map.of(
                "patreon", "https://www.patreon.com/brennanhatton",
                "payment", "https://revolut.me/brennacg7?currency=AUD&amount=2500&note=Dungeon%20Train%20",
                "payment_cn", "https://buy.stripe.com/00w5kDgTL9M31TDc6d8N202",
                "payment_cn_checkout", "https://brennan.games/api/dp-relay/55800db4/pay/cn",
                "payment_checkout", "https://brennan.games/api/dp-relay/55800db4/pay/cn",
                "rewards_pay", "https://brennan.games/api/dp-relay/adc3dc43/rewards/pay",
                "discord", "https://discord.gg/eH9by3bWjw",
                "affiliate", "https://billing.kinetichosting.com/aff.php?aff=1461");
        assertEquals(live, OfficialLinks.sanitize(live));
    }

    @Test
    void anOffListPaymentHostIsDroppedAndTheKeyDegrades() {
        OfficialLinks.accept(Map.of(
                "patreon", "https://evil.example/patreon",
                "payment", "https://www.patreon.com/brennanhatton",
                "payment_checkout", "https://evil.example/pay",
                "rewards_pay", "http://brennan.games/rewards/pay",
                "payment_cn", "https://brennan.games.evil.example/x"));
        assertEquals(OfficialLinks.FALLBACK_PATREON, OfficialLinks.patreon());
        assertEquals(OfficialLinks.FALLBACK_PAYMENT, OfficialLinks.payment(), "a Patreon host is not Revolut's");
        assertNull(OfficialLinks.paymentCheckout(), "price points hide, as offline");
        assertNull(OfficialLinks.rewardsPay(), "Give reads Soon, as offline");
        assertNull(OfficialLinks.paymentCn());
    }

    @Test
    void aFirstPartyRedirectServesAnyPaymentKey() {
        // The relay's /go/<key> redirect is how a new provider reaches already-shipped jars.
        String go = "https://brennan.games/api/dp-relay/cap/go/payment?currency=AUD&note=Dungeon%20Train%20";
        OfficialLinks.accept(Map.of("payment", go, "patreon", "https://dungeon.training/go/patreon"));
        assertEquals(go, OfficialLinks.payment());
        assertEquals("https://dungeon.training/go/patreon", OfficialLinks.patreon());
    }

    // --- sanitize ----------------------------------------------------------------------------

    @Test
    void sanitizeDropsInvalidEntriesAndKeepsValidOnes() {
        Map<String, String> raw = new HashMap<>();
        raw.put("a", "https://ok.example/x");
        raw.put("b", "");
        raw.put("c", null);
        raw.put("d", "ftp://nope.example");
        raw.put("e", "http://plain-http.example");
        Map<String, String> out = OfficialLinks.sanitize(raw);
        assertEquals(Map.of("a", "https://ok.example/x"), out);
    }

    @Test
    void sanitizeOfNullOrEmptyIsEmpty() {
        assertTrue(OfficialLinks.sanitize(null).isEmpty());
        assertTrue(OfficialLinks.sanitize(Map.of()).isEmpty());
    }

    // --- isValidUrl --------------------------------------------------------------------------

    @Test
    void isValidUrlAcceptsHttpsOnly() {
        assertTrue(OfficialLinks.isValidUrl("https://discord.gg/x"));
        assertTrue(OfficialLinks.isValidUrl("https://example.com/a?b=1&c=2"));
        assertFalse(OfficialLinks.isValidUrl("http://example.com/a?b=1&c=2"));
    }

    @Test
    void isValidUrlRejectsBadValues() {
        assertFalse(OfficialLinks.isValidUrl(null));
        assertFalse(OfficialLinks.isValidUrl(""));
        assertFalse(OfficialLinks.isValidUrl("   "));
        assertFalse(OfficialLinks.isValidUrl("discord.gg/x"));
        assertFalse(OfficialLinks.isValidUrl("javascript:alert(1)"));
        assertFalse(OfficialLinks.isValidUrl("https://a b.com"));
        assertFalse(OfficialLinks.isValidUrl("https://" + "x".repeat(600)));
    }
}
