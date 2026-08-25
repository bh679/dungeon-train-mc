package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.chat.RelayChatClient;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Whether this player has had a translation accepted — the signal that unlocks browsing every
 * string unfiltered.
 *
 * <p>Unfiltered browse is not offered to everyone: it turns the editor into a way to read the
 * game's text out of context. Having had a fix accepted is the cheapest honest proof that
 * somebody is here to translate rather than to read ahead.</p>
 *
 * <p>Unlike {@link ApprovedTranslationsFetcher} this carries a uuid, so it is <b>consent-gated</b>
 * like the rest of the uuid-bearing calls. Everything about it fails closed: consent off, request
 * failed, relay older than the endpoint, malformed answer — all leave the extra filter locked.
 * The verdict is cached to disk so an offline launch keeps an unlock the player has already
 * earned, rather than having it flicker off whenever the relay is unreachable.</p>
 */
public final class TranslationContributor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String FILE_NAME = "contributor.txt";

    /** Cached in memory once read; {@code null} until the first {@link #hasApprovedTranslation}. */
    private static Boolean unlocked;
    private static boolean fetchedThisSession;

    private TranslationContributor() {}

    /**
     * Whether the unfiltered filter should be offered. Reads the cached verdict; never blocks on
     * the network, so it is safe to call from screen layout.
     */
    public static synchronized boolean hasApprovedTranslation() {
        if (unlocked == null) {
            unlocked = readCache();
        }
        return unlocked;
    }

    /** Ask the relay once per session. No-throw; a failure simply leaves the cache as it was. */
    public static void refreshOnce() {
        if (fetchedThisSession || !RelayChatClient.canConnect()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null) {
            return;
        }
        fetchedThisSession = true;
        try {
            String url = DungeonTrain.relayBaseUrl() + "/translations/contributor?uuid="
                + uuid.toString().replace("-", "");
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete(TranslationContributor::accept);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Translations: contributor check could not start — {}",
                t.toString());
        }
    }

    private static void accept(HttpResponse<String> resp, Throwable err) {
        try {
            if (err != null || resp == null || resp.statusCode() / 100 != 2) {
                // Includes a relay that predates the endpoint. Keep whatever is cached: an unlock
                // already earned should not be revoked by an unrelated outage.
                LOGGER.debug("[DungeonTrain] Translations: contributor check failed — {}",
                    err != null ? err.toString() : "HTTP " + (resp == null ? "?" : resp.statusCode()));
                return;
            }
            Integer approved = parseApproved(resp.body());
            if (approved == null) {
                return;
            }
            setUnlocked(approved > 0);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Translations: contributor parse failed — {}", t.toString());
        }
    }

    /** The {@code approved} count from {@code {ok, approved}}, or null when malformed. */
    static Integer parseApproved(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) {
            return null;
        }
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("ok") || !obj.get("ok").getAsBoolean()
            || !obj.has("approved") || !obj.get("approved").isJsonPrimitive()
            || !obj.get("approved").getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return obj.get("approved").getAsInt();
    }

    private static synchronized void setUnlocked(boolean value) {
        if (Boolean.valueOf(value).equals(unlocked)) {
            return;
        }
        unlocked = value;
        writeCache(value);
        if (value) {
            LOGGER.info("[DungeonTrain] Translations: approved contribution on record — the "
                + "unfiltered view is unlocked.");
        }
    }

    private static boolean readCache() {
        try {
            Path file = file();
            return file != null && Files.isRegularFile(file)
                && "1".equals(Files.readString(file, StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeCache(boolean value) {
        try {
            Path file = file();
            if (file == null) {
                return;
            }
            if (!value) {
                Files.deleteIfExists(file);
                return;
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, "1", StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] Translations: could not cache the contributor verdict — {}",
                e.toString());
        }
    }

    private static Path file() {
        try {
            return TranslationOverrideStore.root().resolve(FILE_NAME);
        } catch (Exception e) {
            return null;
        }
    }

    /** Test seam: drop the in-memory verdict so the next read hits the cache file again. */
    static synchronized void resetForTesting() {
        unlocked = null;
        fetchedThisSession = false;
    }
}
