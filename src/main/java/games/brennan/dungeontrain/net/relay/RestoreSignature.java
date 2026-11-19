package games.brennan.dungeontrain.net.relay;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Checks that an admin inventory restore really came from the relay.
 *
 * <p>A restore runs no command, so it never makes a run Free Play, and with a {@code run} block it also
 * writes the player's depth and run stats. Without this check, a player who pointed their game at a
 * relay of their own could serve themselves any items, stats and depth. The relay signs each answer
 * with an Ed25519 key only it holds ({@code dp-relay/restore-signing.js}); the public half is baked in
 * here. The private key is {@code ~/.config/dungeontrain/restore-signing-key.pem} on Brennan's Mac and
 * {@code RESTORE_SIGNING_KEY_FILE} on the relay box.</p>
 */
public final class RestoreSignature {

    /** X.509 DER, base64: the relay's restore-signing public key. */
    static final String PUBLIC_KEY_B64 = "MCowBQYDK2VwAyEAn0FSK6HDXGDSgf3JYmi07TBqy9ZYOXvHH1byrPkKg8s=";

    private static final PublicKey RELAY_KEY = decode(PUBLIC_KEY_B64);

    private RestoreSignature() {}

    static PublicKey relayKey() {
        return RELAY_KEY;
    }

    /** Whether {@code sigB64} is {@code key}'s Ed25519 signature over the UTF-8 bytes of {@code signed}. Never throws. */
    static boolean verify(PublicKey key, String signed, String sigB64) {
        if (key == null || signed == null || sigB64 == null) return false;
        try {
            Signature v = Signature.getInstance("Ed25519");
            v.initVerify(key);
            v.update(signed.getBytes(StandardCharsets.UTF_8));
            return v.verify(Base64.getDecoder().decode(sigB64));
        } catch (Exception e) {
            return false;
        }
    }

    static PublicKey decode(String b64) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (Exception e) {
            return null;
        }
    }
}
