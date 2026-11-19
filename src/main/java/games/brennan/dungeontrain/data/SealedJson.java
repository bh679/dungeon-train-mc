package games.brennan.dungeontrain.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A seal over one of the player's progress files, so a hand edit is noticed on the next read.
 *
 * <p>The file keeps its ordinary JSON shape and gains two fields: {@code seal}, an HMAC-SHA256 under
 * {@link InstallKey} over the rest of the object plus a scope naming which store, player and
 * partition the file belongs to (a sealed file copied between players or difficulties fails), and
 * {@code tainted}, set once a store has caught the file edited, which the seal then covers so it
 * stays set until the file is reset.</p>
 *
 * <p>Reading gives one of three {@link Provenance}s. {@code VERIFIED} is a seal that matches, or an
 * unsealed file on an install that had never sealed before this session (an upgrade). {@code UNVERIFIED}
 * is an unsealed file on an install that had: the seal was stripped, or an older backup was put
 * back. Neither can be told apart, so the contents are kept but not vouched for. {@code TAMPERED} is
 * a seal that does not match: the contents were changed.</p>
 */
public final class SealedJson {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String SEAL = "seal";
    static final String TAINTED = "tainted";
    private static final String ALGORITHM = "HmacSHA256";

    public enum Provenance { VERIFIED, UNVERIFIED, TAMPERED }

    /** What a read found: where the contents stand, and whether the file carries a taint mark. */
    public record Verdict(Provenance provenance, boolean tainted) {
        public boolean verified() { return provenance == Provenance.VERIFIED; }
    }

    private SealedJson() {}

    /** The scope a store's file is sealed under: which store, whose, which partition. */
    public static String scope(String store, UUID uuid, String partition) {
        return store + "|" + uuid + "|" + partition;
    }

    /** {@code payload} with its {@code seal} (and {@code tainted}, when set) fields written. */
    public static JsonObject seal(JsonObject payload, String scope, boolean tainted) {
        JsonObject out = payload.deepCopy();
        out.remove(SEAL);
        out.remove(TAINTED);
        if (tainted) out.addProperty(TAINTED, true);
        out.addProperty(SEAL, mac(scope, canonical(out)));
        return out;
    }

    /** Judge a file as read from disk. */
    public static Verdict verify(JsonObject file, String scope) {
        JsonElement seal = file.get(SEAL);
        boolean tainted = file.has(TAINTED) && file.get(TAINTED).isJsonPrimitive()
            && file.get(TAINTED).getAsJsonPrimitive().isBoolean() && file.get(TAINTED).getAsBoolean();
        if (seal == null || !seal.isJsonPrimitive()) {
            return new Verdict(InstallKey.sealedBefore() ? Provenance.UNVERIFIED : Provenance.VERIFIED, false);
        }
        JsonObject body = file.deepCopy();
        body.remove(SEAL);
        String expected = mac(scope, canonical(body));
        boolean matches = MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII), seal.getAsString().getBytes(StandardCharsets.US_ASCII));
        return matches ? new Verdict(Provenance.VERIFIED, tainted) : new Verdict(Provenance.TAMPERED, true);
    }

    private static String mac(String scope, String canonical) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(InstallKey.key(), ALGORITHM));
            mac.update(scope.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '\n');
            mac.update(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            LOGGER.error("[DungeonTrain] SealedJson: {} unavailable", ALGORITHM, e);
            return "";
        }
    }

    /** The object with every key sorted, so formatting and field order never change the seal. */
    static String canonical(JsonElement element) {
        return sorted(element).toString();
    }

    private static JsonElement sorted(JsonElement element) {
        if (element.isJsonObject()) {
            Map<String, JsonElement> entries = new TreeMap<>();
            element.getAsJsonObject().entrySet().forEach(e -> entries.put(e.getKey(), sorted(e.getValue())));
            JsonObject out = new JsonObject();
            entries.forEach(out::add);
            return out;
        }
        if (element.isJsonArray()) {
            JsonArray out = new JsonArray();
            element.getAsJsonArray().forEach(e -> out.add(sorted(e)));
            return out;
        }
        return element;
    }
}
