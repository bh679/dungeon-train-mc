package games.brennan.dungeontrain.client.links;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LinkPolicy}: the shape check every outbound link must pass (payment provenance is signed). */
class LinkPolicyTest {

    @BeforeEach
    void noLocalRelay() {
        LinkPolicy.setLocalRelayForTest(false);
    }

    @AfterEach
    void reread() {
        LinkPolicy.setLocalRelayForTest(null);
    }

    @Test
    void everyBakedFallbackPassesItsOwnKey() {
        Map<String, String> baked = Map.of(
                "discord", OfficialLinks.FALLBACK_DISCORD,
                "patreon", OfficialLinks.FALLBACK_PATREON,
                "payment", OfficialLinks.FALLBACK_PAYMENT,
                "affiliate", OfficialLinks.FALLBACK_AFFILIATE,
                "bilibili", OfficialLinks.FALLBACK_BILIBILI,
                "youtube", OfficialLinks.FALLBACK_YOUTUBE,
                "instagram", OfficialLinks.FALLBACK_INSTAGRAM,
                "mod_whitelist", OfficialLinks.FALLBACK_MOD_WHITELIST);
        baked.forEach((key, url) -> assertTrue(LinkPolicy.allowed(key, url), key));
    }

    @Test
    void theShapeCheckIsTheSameForEveryKey() {
        // Where a payment link came from is its signature's job (PaymentLinkSignature); this only
        // judges shape, so any well-formed https host passes for every key.
        for (String k : LinkPolicy.PAYMENT_KEYS) {
            assertTrue(LinkPolicy.allowed(k, "https://pay.example.com/new-provider"), k);
            assertTrue(LinkPolicy.allowed(k, "https://brennan.games:443/pay"), k);
        }
    }

    @Test
    void malformedAndTrickLinksAreRejectedForEveryKey() {
        String[] bad = {
                "http://brennan.games/pay",
                "https://brennan.games@evil.com/pay",
                "https://user:pw@brennan.games/pay",
                "https://brennan.games:8443/pay",
                "https://brennan.games\\@evil.com/",
                "https://bréñnan.games/x",
                "javascript:alert(1)",
                "https:///nohost",
                "not a url",
                "",
                null,
        };
        for (String k : new String[]{"payment", "patreon", "rewards_pay", "discord", null}) {
            for (String url : bad) assertFalse(LinkPolicy.allowed(k, url), k + " " + url);
        }
    }

    @Test
    void paymentKeysAreExactlyTheMoneyButtons() {
        assertEquals(java.util.Set.of("patreon", "payment", "payment_cn", "payment_cn_checkout",
                "payment_checkout", "rewards_pay"), LinkPolicy.PAYMENT_KEYS);
        assertFalse(LinkPolicy.isPaymentKey("discord"));
        assertFalse(LinkPolicy.isPaymentKey(null));
    }

    @Test
    void firstPartyIsOurDomainsAndTheirSubdomains() {
        assertTrue(LinkPolicy.isFirstParty("brennan.games"));
        assertTrue(LinkPolicy.isFirstParty("x.dungeon.training"));
        assertFalse(LinkPolicy.isFirstParty("brennan.games.evil.com"));
        assertFalse(LinkPolicy.isFirstParty("notbrennan.games"));
    }

    @Test
    void nonPaymentKeysTakeAnyHttpsHost() {
        assertTrue(LinkPolicy.allowed("discord", "https://discord.gg/new"));
        assertTrue(LinkPolicy.allowed("affiliate", "https://some-new-host.example/aff"));
        assertTrue(LinkPolicy.allowed(null, "https://anything.example/x"));
        assertFalse(LinkPolicy.allowed("discord", "http://discord.gg/new"));
        assertFalse(LinkPolicy.allowed("discord", "https://u@discord.gg/new"));
    }

    @Test
    void loopbackHttpOnlyWithTheLocalRelayOverride() {
        String local = "http://127.0.0.1:8787/api/dp-relay/cap/pay/cn";
        assertFalse(LinkPolicy.allowed("payment_checkout", local));
        LinkPolicy.setLocalRelayForTest(true);
        assertTrue(LinkPolicy.allowed("payment_checkout", local));
        assertTrue(LinkPolicy.allowed("rewards_pay", "http://localhost:8787/rewards/pay"));
        assertFalse(LinkPolicy.allowed("payment_checkout", "http://evil.com/pay"), "not loopback");
        assertFalse(LinkPolicy.allowed("discord", "http://evil.com/"), "http stays loopback-only");
        assertTrue(LinkPolicy.isLocalRelayLoopback(local));
        assertFalse(LinkPolicy.isLocalRelayLoopback("https://buy.stripe.com/x"));
        LinkPolicy.setLocalRelayForTest(false);
        assertFalse(LinkPolicy.isLocalRelayLoopback(local));
    }

    @Test
    void hostOfIsTheBareLowerCasedHost() {
        assertEquals("brennan.games", LinkPolicy.hostOf("https://Brennan.Games/api/x?displayname=Steve"));
        assertNull(LinkPolicy.hostOf("not a url"));
        assertNull(LinkPolicy.hostOf(null));
    }
}
