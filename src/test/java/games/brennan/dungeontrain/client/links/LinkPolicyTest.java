package games.brennan.dungeontrain.client.links;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LinkPolicy}: https only everywhere, plus a host allowlist on the payment keys. */
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
    void eachProviderOnlyOnItsOwnKeys() {
        assertTrue(LinkPolicy.allowed("patreon", "https://www.patreon.com/x"));
        assertTrue(LinkPolicy.allowed("patreon", "https://patreon.com/x"));
        assertTrue(LinkPolicy.allowed("payment", "https://revolut.me/x"));
        for (String k : new String[]{"payment_cn", "payment_cn_checkout", "payment_checkout", "rewards_pay"}) {
            assertTrue(LinkPolicy.allowed(k, "https://buy.stripe.com/x"), k);
            assertTrue(LinkPolicy.allowed(k, "https://checkout.stripe.com/c/pay/cs_1"), k);
            assertFalse(LinkPolicy.allowed(k, "https://revolut.me/x"), k);
        }
        assertFalse(LinkPolicy.allowed("payment", "https://www.patreon.com/x"));
        assertFalse(LinkPolicy.allowed("patreon", "https://buy.stripe.com/x"));
    }

    @Test
    void firstPartyHostsAndSubdomainsServeEveryPaymentKey() {
        for (String k : LinkPolicy.PAYMENT_HOSTS.keySet()) {
            assertTrue(LinkPolicy.allowed(k, "https://brennan.games/api/dp-relay/c/go/" + k), k);
            assertTrue(LinkPolicy.allowed(k, "https://x.brennan.games/pay"), k);
            assertTrue(LinkPolicy.allowed(k, "https://dungeon.training/pay"), k);
            assertTrue(LinkPolicy.allowed(k, "https://brennan.games:443/pay"), k);
        }
    }

    @Test
    void hostCaseDoesNotMatter() {
        assertTrue(LinkPolicy.allowed("patreon", "HTTPS://WWW.PATREON.COM/x"));
    }

    @Test
    void lookalikesAndTricksAreRejectedOnPaymentKeys() {
        String[] bad = {
                "http://brennan.games/pay",
                "https://evil.com/pay",
                "https://brennan.games.evil.com/pay",
                "https://brennan.games@evil.com/pay",
                "https://user:pw@brennan.games/pay",
                "https://evil.com/brennan.games",
                "https://evil.com/?h=brennan.games",
                "https://brennan.games:8443/pay",
                "https://revolut.me.evil.com/x",
                "https://notbrennan.games/x",
                "https://brennan.games\\@evil.com/",
                "https://bréñnan.games/x",
                "javascript:alert(1)",
                "https:///nohost",
                "not a url",
                "",
                null,
        };
        for (String k : LinkPolicy.PAYMENT_HOSTS.keySet()) {
            for (String url : bad) assertFalse(LinkPolicy.allowed(k, url), k + " " + url);
        }
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
    }

    @Test
    void hostOfIsTheBareLowerCasedHost() {
        assertEquals("brennan.games", LinkPolicy.hostOf("https://Brennan.Games/api/x?displayname=Steve"));
        assertNull(LinkPolicy.hostOf("not a url"));
        assertNull(LinkPolicy.hostOf(null));
    }
}
