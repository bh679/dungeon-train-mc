package games.brennan.dungeontrain.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
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
 * unsealed file that is this install's own history from before it sealed: either the install had
 * never sealed before this session (an upgrade), or the file was last written before the install
 * key was made ({@link InstallKey#createdAt}). The second rule matters because a file is only
 * sealed when it is next written, and a partition the upgrade session never touched (another
 * difficulty, the shared file, another player) would otherwise turn unverified the session after.
 * {@code UNVERIFIED} is an unsealed file written after the key existed: the seal was stripped, or an
 * older backup was put back. Neither can be told apart, so the contents are kept but not vouched
 * for. {@code TAMPERED} is a seal that does not match: the contents were changed.</p>
 *
 * <p>A store that reads an unsealed file as {@code VERIFIED} should write it back sealed
 * ({@link Verdict#unsealed}), so it never has to lean on the file date again.</p>
 */
public final class SealedJson {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String SEAL = "seal";
    static final String TAINTED = "tainted";
    private static final String ALGORITHM = "HmacSHA256";

    public enum Provenance { VERIFIED, UNVERIFIED, TAMPERED }

    /**
     * What a read found: where the contents stand, whether the file carries a taint mark, and
     * whether it had no seal at all (so a {@code VERIFIED} one wants sealing on the spot).
     */
    public record Verdict(Provenance provenance, boolean tainted, boolean unsealed) {
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

    /** Judge a file whose date is unknown: an unsealed one is decided by {@link InstallKey#sealedBefore} alone. */
    public static Verdict verify(JsonObject file, String scope) {
        return verify(file, scope, (Instant) null);
    }

    /** Judge a file read from {@code source} on disk; its date decides an unsealed file. */
    public static Verdict verify(JsonObject file, String scope, Path source) {
        return verify(file, scope, lastModified(source));
    }

    /**
     * Judge a file as read. {@code lastModified} is when it was last written, or {@code null} when
     * unknown, which judges an unsealed file by {@link InstallKey#sealedBefore} alone.
     */
    public static Verdict verify(JsonObject file, String scope, Instant lastModified) {
        JsonElement seal = file.get(SEAL);
        boolean tainted = file.has(TAINTED) && file.get(TAINTED).isJsonPrimitive()
            && file.get(TAINTED).getAsJsonPrimitive().isBoolean() && file.get(TAINTED).getAsBoolean();
        if (seal == null || !seal.isJsonPrimitive()) {
            return new Verdict(isOwnHistory(lastModified) ? Provenance.VERIFIED : Provenance.UNVERIFIED, false, true);
        }
        JsonObject body = file.deepCopy();
        body.remove(SEAL);
        String expected = mac(scope, canonical(body));
        boolean matches = MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII), seal.getAsString().getBytes(StandardCharsets.US_ASCII));
        return matches ? new Verdict(Provenance.VERIFIED, tainted, false) : new Verdict(Provenance.TAMPERED, true, false);
    }

    /** Is an unsealed file written at {@code lastModified} from before this install ever sealed? */
    private static boolean isOwnHistory(Instant lastModified) {
        if (!InstallKey.sealedBefore()) return true;
        Instant keyMade = InstallKey.createdAt();
        return lastModified != null && keyMade != null && !lastModified.isAfter(keyMade);
    }

    private static Instant lastModified(Path source) {
        try {
            return Files.getLastModifiedTime(source).toInstant();
        } catch (IOException | RuntimeException e) {
            return null;
        }
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
