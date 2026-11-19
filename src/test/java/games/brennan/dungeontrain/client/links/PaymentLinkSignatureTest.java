package games.brennan.dungeontrain.client.links;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link PaymentLinkSignature}: a relay payment link is used only when signed by the payment-link key
 * for exactly the slot it is served in. A throwaway key pair stands in for the real one.
 */
class PaymentLinkSignatureTest {

    private static final String URL = "https://pay.example.com/brennan?note=Dungeon%20Train%20";

    private KeyPair pair;
    private KeyPair other;

    @BeforeEach
    void keys() throws Exception {
        pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PaymentLinkSignature.setKeysForTest(List.of(
                Base64.getEncoder().encodeToString(other.getPublic().getEncoded()),
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())));
        LinkPolicy.setLocalRelayForTest(false);
    }

    @AfterEach
    void restore() {
        PaymentLinkSignature.setKeysForTest(null);
        LinkPolicy.setLocalRelayForTest(null);
    }

    private static String payload(String key, String url) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("key", key);
        o.addProperty("url", url);
        o.addProperty("iat", 1795060948021L);
        return o.toString();
    }

    private static String sign(PrivateKey k, String signed) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(k);
        s.update(signed.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    @Test
    void theBakedKeyDecodes() {
        PaymentLinkSignature.setKeysForTest(null);
        assertEquals(PaymentLinkSignature.PUBLIC_KEYS_B64.size(), PaymentLinkSignature.keyCount());
    }

    @Test
    void aBlobFromTheRelaysSigningScriptVerifiesAgainstTheBakedKey() {
        // Made by dp-relay scripts/links/sign-payment-link.js --dry-run with the real key: pins that
        // Node's signing and this verifier agree byte for byte, and that the baked key is the right one.
        PaymentLinkSignature.setKeysForTest(null);
        String signed = "{\"v\":1,\"key\":\"payment\",\"url\":\"https://revolut.me/brennacg7?currency=AUD&amount=2500&note=Dungeon%20Train%20\",\"iat\":1795060948021}";
        String sig = "vFdDUzNpe7QJ7utkYPuxXqC5c+Rsf7losmqySua87Xm+rK4o+rherFEIZbwCxh2hL8zbIsC989cc4fClNgZoCA==";
        assertEquals("https://revolut.me/brennacg7?currency=AUD&amount=2500&note=Dungeon%20Train%20",
                PaymentLinkSignature.verifiedUrl("payment", signed, sig));
        assertNull(PaymentLinkSignature.verifiedUrl("patreon", signed, sig));
    }

    @Test
    void aValidSignatureYieldsTheUrlInsideIt() throws Exception {
        String signed = payload("payment", URL);
        assertEquals(URL, PaymentLinkSignature.verifiedUrl("payment", signed, sign(pair.getPrivate(), signed)));
    }

    @Test
    void anyListedKeyVerifies() throws Exception {
        String signed = payload("patreon", URL);
        assertEquals(URL, PaymentLinkSignature.verifiedUrl("patreon", signed, sign(other.getPrivate(), signed)));
    }

    @Test
    void aSignatureFromAnUnlistedKeyIsRejected() throws Exception {
        KeyPair stranger = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String signed = payload("payment", URL);
        assertNull(PaymentLinkSignature.verifiedUrl("payment", signed, sign(stranger.getPrivate(), signed)));
    }

    @Test
    void aTamperedPayloadIsRejected() throws Exception {
        String signed = payload("payment", URL);
        String sig = sign(pair.getPrivate(), signed);
        assertNull(PaymentLinkSignature.verifiedUrl("payment", signed.replace("pay.example.com", "evil.example.com"), sig));
    }

    @Test
    void aSignatureForOneButtonCannotBeMovedOntoAnother() throws Exception {
        String signed = payload("patreon", URL);
        assertNull(PaymentLinkSignature.verifiedUrl("payment", signed, sign(pair.getPrivate(), signed)));
    }

    @Test
    void nonPaymentKeysAndBadShapesAreRejected() throws Exception {
        String discord = payload("discord", "https://discord.gg/x");
        assertNull(PaymentLinkSignature.verifiedUrl("discord", discord, sign(pair.getPrivate(), discord)));
        String http = payload("payment", "http://pay.example.com/x");
        assertNull(PaymentLinkSignature.verifiedUrl("payment", http, sign(pair.getPrivate(), http)), "signed but not https");
        String v2 = payload("payment", URL).replace("\"v\":1", "\"v\":2");
        assertNull(PaymentLinkSignature.verifiedUrl("payment", v2, sign(pair.getPrivate(), v2)));
    }

    @Test
    void garbageNeverThrows() {
        for (String sig : new String[]{null, "", "not base64!", "AAAA"}) {
            assertNull(PaymentLinkSignature.verifiedUrl("payment", payload("payment", URL), sig));
        }
        assertNull(PaymentLinkSignature.verifiedUrl("payment", null, "AAAA"));
        assertNull(PaymentLinkSignature.verifiedUrl(null, payload("payment", URL), "AAAA"));
    }

    @Test
    void verifiedUrlsKeepsOnlyTheGoodEntries() throws Exception {
        String good = payload("payment_cn", "https://buy.stripe.com/test_x");
        String moved = payload("patreon", URL);
        JsonObject map = JsonParser.parseString("{"
                + "\"payment_cn\":{\"signed\":" + quote(good) + ",\"sig\":\"" + sign(pair.getPrivate(), good) + "\"},"
                + "\"payment\":{\"signed\":" + quote(moved) + ",\"sig\":\"" + sign(pair.getPrivate(), moved) + "\"},"
                + "\"rewards_pay\":\"not an object\""
                + "}").getAsJsonObject();
        assertEquals(Map.of("payment_cn", "https://buy.stripe.com/test_x"), PaymentLinkSignature.verifiedUrls(map));
    }

    @Test
    void theFetcherReadsTheSignedMapAndIgnoresAMissingOne() throws Exception {
        String good = payload("payment", URL);
        String body = "{\"ok\":true,\"links\":{\"payment\":\"https://evil.example/x\"},\"signed\":{\"payment\":{\"signed\":"
                + quote(good) + ",\"sig\":\"" + sign(pair.getPrivate(), good) + "\"}}}";
        assertEquals(Map.of("payment", URL), OfficialLinksFetcher.parseSigned(body));
        assertEquals(Map.of(), OfficialLinksFetcher.parseSigned("{\"ok\":true,\"links\":{}}"));
        assertEquals(Map.of(), OfficialLinksFetcher.parseSigned("not json"));
    }

    private static String quote(String s) {
        JsonObject o = new JsonObject();
        o.addProperty("s", s);
        String j = o.toString();
        return j.substring(5, j.length() - 1);
    }
}
