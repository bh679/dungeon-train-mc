package games.brennan.dungeontrain.client.links;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    }

    @Test
    void paymentFallbackDefaultsTo25Aud() {
        assertTrue(OfficialLinks.payment().contains("amount=2500"),
                "default direct-donation amount should be $25 AUD (2500 cents)");
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

    @Test
    void overlayValuesAreTrimmed() {
        OfficialLinks.accept(Map.of("affiliate", "  https://example.com/aff  "));
        assertEquals("https://example.com/aff", OfficialLinks.affiliate());
    }

    // --- sanitize ----------------------------------------------------------------------------

    @Test
    void sanitizeDropsInvalidEntriesAndKeepsValidOnes() {
        Map<String, String> raw = new HashMap<>();
        raw.put("a", "https://ok.example/x");
        raw.put("b", "");
        raw.put("c", null);
        raw.put("d", "ftp://nope.example");
        raw.put("e", "http://also-ok.example");
        Map<String, String> out = OfficialLinks.sanitize(raw);
        assertEquals(Map.of("a", "https://ok.example/x", "e", "http://also-ok.example"), out);
    }

    @Test
    void sanitizeOfNullOrEmptyIsEmpty() {
        assertTrue(OfficialLinks.sanitize(null).isEmpty());
        assertTrue(OfficialLinks.sanitize(Map.of()).isEmpty());
    }

    // --- isValidUrl --------------------------------------------------------------------------

    @Test
    void isValidUrlAcceptsHttpAndHttps() {
        assertTrue(OfficialLinks.isValidUrl("https://discord.gg/x"));
        assertTrue(OfficialLinks.isValidUrl("http://example.com/a?b=1&c=2"));
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
