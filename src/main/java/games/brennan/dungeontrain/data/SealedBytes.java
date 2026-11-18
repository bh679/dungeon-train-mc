package games.brennan.dungeontrain.data;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * The HMAC-SHA256 under {@link InstallKey} that every sealed file here is signed with: over a scope
 * naming the store, a newline, then the bytes. {@link SealedJson} seals its JSON through this; binary
 * stores (the nap list) seal their bytes directly. Same honest limit as the key: it stops hand
 * edits, not someone who reads this code.
 */
public final class SealedBytes {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ALGORITHM = "HmacSHA256";
    /** Length of {@link #mac} when the algorithm is available. */
    public static final int MAC_BYTES = 32;

    private SealedBytes() {}

    /** The seal of {@code data} under {@code scope}; empty only when HmacSHA256 is unavailable. */
    public static byte[] mac(String scope, byte[] data) {
        return mac(scope, data, 0, data.length);
    }

    /** The seal of {@code data[offset, offset + length)} under {@code scope}. */
    public static byte[] mac(String scope, byte[] data, int offset, int length) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(InstallKey.key(), ALGORITHM));
            mac.update(scope.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '\n');
            mac.update(data, offset, length);
            return mac.doFinal();
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            LOGGER.error("[DungeonTrain] SealedBytes: {} unavailable", ALGORITHM, e);
            return new byte[0];
        }
    }

    /** Constant-time: does {@code seal} match {@code data[offset, offset + length)} under {@code scope}? */
    public static boolean matches(String scope, byte[] data, int offset, int length, byte[] seal) {
        byte[] expected = mac(scope, data, offset, length);
        return expected.length > 0 && MessageDigest.isEqual(expected, seal);
    }
}
