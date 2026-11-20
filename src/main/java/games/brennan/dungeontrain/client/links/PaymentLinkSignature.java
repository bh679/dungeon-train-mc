package games.brennan.dungeontrain.client.links;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.util.StrictBase64;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks that a relay-served payment link was signed by Brennan's payment-link key.
 *
 * <p>A payment link decides where a player's money goes, so the relay alone is not trusted with it.
 * Each one is signed on Brennan's Mac with an Ed25519 key that never touches the relay box
 * ({@code ~/.config/dungeontrain/payment-link-signing-key.pem}, backed up in 1Password); the relay only
 * stores and serves the signatures ({@code dp-relay/link-signatures.js}). A leaked admin key, a bad
 * edit or a compromised relay therefore cannot point a money button anywhere new. A new provider still
 * needs no release: Brennan signs the new link and the relay serves it.</p>
 *
 * <p>What is signed is the exact UTF-8 bytes of {@code signed}: JSON {@code {v:1, key, url, iat}}. The
 * URL is read from inside that string, never from a field beside it, and {@code key} must be the slot
 * it was served in, so a signature for one button cannot be moved onto another.</p>
 *
 * <p>Pure: no Minecraft on any path.</p>
 */
public final class PaymentLinkSignature {

    /**
     * X.509 DER base64 public keys. A list so a key rotation can ship the new key beside the old one.
     * Keep in lock-step with {@code PUBLIC_KEYS_B64} in the relay's {@code link-signatures.js}.
     */
    static final List<String> PUBLIC_KEYS_B64 = List.of(
            "MCowBQYDK2VwAyEANP3K+0XZ+OHTqCkjZ51BFGwMtHJYgsCk4Q6GbDCfRgE=");

    private static final int MAX_SIGNED = 1000;

    private static volatile List<PublicKey> keys = decodeAll(PUBLIC_KEYS_B64);

    private PaymentLinkSignature() {}

    /**
     * Payment key → URL for every entry of the relay's {@code signed} map that verifies, belongs to
     * the key it is filed under and is a well-formed link. Anything else is left out. Never throws.
     */
    static Map<String, String> verifiedUrls(JsonObject signedMap) {
        Map<String, String> out = new HashMap<>();
        if (signedMap == null) return out;
        for (Map.Entry<String, JsonElement> e : signedMap.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject o = e.getValue().getAsJsonObject();
            String url = verifiedUrl(e.getKey(), string(o, "signed"), string(o, "sig"));
            if (url != null) out.put(e.getKey(), url);
        }
        return Map.copyOf(out);
    }

    /** The URL inside {@code signed} when it verifies and was signed for {@code key}, else null. */
    static String verifiedUrl(String key, String signed, String sigB64) {
        if (!LinkPolicy.isPaymentKey(key) || signed == null || signed.length() > MAX_SIGNED) return null;
        if (!verify(signed, sigB64)) return null;
        try {
            JsonElement root = JsonParser.parseString(signed);
            if (!root.isJsonObject()) return null;
            JsonObject p = root.getAsJsonObject();
            JsonElement v = p.get("v");
            if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber() || v.getAsDouble() != 1) return null;
            if (!key.equals(string(p, "key"))) return null;
            String url = string(p, "url");
            return url != null && LinkPolicy.allowed(key, url) ? url : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether {@code sigB64} is one of the baked keys' Ed25519 signature over {@code signed}. */
    static boolean verify(String signed, String sigB64) {
        if (signed == null || sigB64 == null) return false;
        byte[] sig;
        try {
            sig = StrictBase64.decode(sigB64);
        } catch (RuntimeException e) {
            return false;
        }
        byte[] data = signed.getBytes(StandardCharsets.UTF_8);
        for (PublicKey k : keys) {
            try {
                Signature v = Signature.getInstance("Ed25519");
                v.initVerify(k);
                v.update(data);
                if (v.verify(sig)) return true;
            } catch (Exception ignored) {
                // next key
            }
        }
        return false;
    }

    /** How many baked keys decoded (a test pins that none is malformed). */
    static int keyCount() {
        return keys.size();
    }

    /** Test seam: verify against these X.509 base64 keys instead (null restores the baked ones). */
    static void setKeysForTest(List<String> b64s) {
        keys = decodeAll(b64s == null ? PUBLIC_KEYS_B64 : b64s);
    }

    private static String string(JsonObject o, String field) {
        JsonElement e = o.get(field);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static List<PublicKey> decodeAll(List<String> b64s) {
        List<PublicKey> out = new ArrayList<>();
        for (String b64 : b64s) {
            try {
                out.add(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(StrictBase64.decode(b64))));
            } catch (Exception ignored) {
                // a malformed key is skipped; keyCount() lets a test catch it
            }
        }
        return List.copyOf(out);
    }
}
