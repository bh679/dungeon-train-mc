package games.brennan.dungeontrain.progress;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The client's persisted handle on its relay progress account, at
 * {@code <minecraft>/config/dungeontrain-progress-account.json}.
 *
 * <p>Deliberately its own file rather than a row in {@code ClientDisplayConfig}: it holds a bearer
 * secret, and a secret does not belong in a config file players are invited to open and edit. Keeping
 * it separate also means a player can hand-delete it to start a fresh account, or copy it between
 * machines, without touching their settings.</p>
 *
 * <p>Written atomically (tmp + rename) like {@link games.brennan.dungeontrain.net.relay.RelayOutbox}.
 * Best-effort throughout: a missing or corrupt file reads as "no account yet", which sends the caller
 * back through the handshake rather than throwing into the login path.</p>
 */
public final class ProgressAccountFile {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "dungeontrain-progress-account.json";

    /** Cached so login doesn't re-read the file; {@code null} means "not loaded yet". */
    private static volatile ProgressCredential cached;
    private static volatile boolean loaded;

    private ProgressAccountFile() {}

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    /** The stored credential, or {@code null} when this install has never claimed an account. */
    public static synchronized ProgressCredential load() {
        if (loaded) return cached;
        loaded = true;
        cached = readFromDisk();
        return cached;
    }

    /** Persist {@code credential} as this install's account. No-throw. */
    public static synchronized void save(ProgressCredential credential) {
        cached = credential;
        loaded = true;
        if (credential == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("accountId", credential.accountId());
        o.addProperty("kind", credential.kind());
        if (credential.principal() != null) o.addProperty("principal", credential.principal());
        o.addProperty("secret", credential.secret());
        if (credential.uuid() != null) o.addProperty("uuid", credential.uuid());
        if (credential.name() != null) o.addProperty("name", credential.name());

        Path path = file();
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(tmp)) {
                w.write(o.toString());
            }
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            LOGGER.info("[DungeonTrain] progress account stored ({} account {}).",
                    credential.kind(), credential.accountId());
        } catch (IOException e) {
            // A player who can't persist the credential still plays; they just re-handshake next launch.
            LOGGER.warn("[DungeonTrain] could not write {}: {}", path, e.getMessage());
        }
    }

    /** Test/reset seam — forget the cached credential so the next {@link #load} re-reads the file. */
    public static synchronized void invalidate() {
        loaded = false;
        cached = null;
    }

    private static ProgressCredential readFromDisk() {
        Path path = file();
        if (!Files.isRegularFile(path)) return null;
        try (Reader r = Files.newBufferedReader(path)) {
            JsonElement el = JsonParser.parseReader(r);
            if (el == null || !el.isJsonObject()) return null;
            JsonObject o = el.getAsJsonObject();
            ProgressCredential c = new ProgressCredential(
                    str(o, "accountId"), str(o, "kind"), str(o, "principal"),
                    str(o, "secret"), str(o, "uuid"), str(o, "name"));
            return c.isUsable() ? c : null;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] malformed {} — starting a fresh progress account ({})",
                    path.getFileName(), e.getMessage());
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }
}
