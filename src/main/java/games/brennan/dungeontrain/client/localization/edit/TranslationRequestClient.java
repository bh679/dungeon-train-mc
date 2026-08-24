package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.client.chat.RelayChatClient;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Asks the relay for a machine first draft of a language nobody has translated yet
 * ({@code POST /<CAP>/translations/request}), and reads back how many other players have asked for
 * the same one ({@code GET /<CAP>/translations/requests?locale=}).
 *
 * <p>This does not translate anything and nothing at the other end does either. The machine pass is
 * an offline, repo-side job, and the thing it has always lacked is a reason to prefer one of the
 * hundred-odd untranslated languages over another. That is all this collects: a tally of who is
 * actually sitting in front of the game wanting one.</p>
 *
 * <p>No outbox and no retry, for {@link TranslationDismissClient}'s reason — a request that never
 * lands costs the player one press, and a durable queue for a vote would be machinery in exchange
 * for nothing. A relay older than the endpoint simply fails quiet. Carries a uuid, so it is
 * consent-gated like every other uuid-bearing call.</p>
 */
public final class TranslationRequestClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private TranslationRequestClient() {}

    /**
     * The {@code /translations/request} body. Pure, so the shape can be tested without a relay —
     * {@link TranslationDismissClient#buildPayload} is the model.
     *
     * <p>No unit list and no text: the whole claim is "this player wants this language", so there
     * is nothing here for a moderator to read and nothing to moderate.</p>
     */
    static JsonObject buildPayload(String uuid, String locale, String modVersion) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid == null ? "" : uuid);
        body.addProperty("locale", locale == null ? "" : locale.toLowerCase(Locale.ROOT));
        body.addProperty("modVersion", modVersion == null ? "" : modVersion);
        return body;
    }

    /**
     * Register a request for {@code locale}. Never throws, never blocks, never retries.
     *
     * <p>The local record is written by the caller before this runs, and deliberately not undone
     * when the send fails: the button's job is to acknowledge the press, and a relay that missed
     * one vote is a smaller problem than a button that appears to have done nothing.</p>
     */
    public static void send(String locale, IntConsumer onCount) {
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null || locale == null || locale.isBlank() || !RelayChatClient.canConnect()) {
            return;
        }
        try {
            String json =
                buildPayload(uuid.toString().replace("-", ""), locale, VersionInfo.VERSION).toString();
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create(DungeonTrain.relayBaseUrl() + "/translations/request"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    if (err != null || resp == null || resp.statusCode() / 100 != 2) {
                        LOGGER.debug("[DungeonTrain] Translations: request not recorded — {}",
                            err != null ? err.toString()
                                : "HTTP " + (resp == null ? "?" : resp.statusCode()));
                        return;
                    }
                    deliver(readCount(resp.body()), onCount);
                });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Translations: request could not be sent — {}", t.toString());
        }
    }

    /**
     * How many players have asked for {@code locale}, handed to {@code onCount} on the client
     * thread. Silent on any failure — the button reads perfectly well without a number, and an
     * error where a count should be would be worse than no count.
     */
    public static void fetchCount(String locale, IntConsumer onCount) {
        if (locale == null || locale.isBlank() || !RelayChatClient.canConnect()) {
            return;
        }
        try {
            String query = URLEncoder.encode(locale.toLowerCase(Locale.ROOT), StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create(DungeonTrain.relayBaseUrl() + "/translations/requests?locale=" + query))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    if (err != null || resp == null || resp.statusCode() / 100 != 2) {
                        LOGGER.debug("[DungeonTrain] Translations: request count unavailable — {}",
                            err != null ? err.toString()
                                : "HTTP " + (resp == null ? "?" : resp.statusCode()));
                        return;
                    }
                    deliver(readCount(resp.body()), onCount);
                });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Translations: request count failed — {}", t.toString());
        }
    }

    /** {@code {"locale":"hu_hu","count":12}} → 12, or -1 for anything this cannot read. */
    static int readCount(String body) {
        try {
            var root = JsonParser.parseString(body == null ? "" : body);
            if (!root.isJsonObject()) {
                return -1;
            }
            var count = root.getAsJsonObject().get("count");
            return count != null && count.isJsonPrimitive() ? count.getAsInt() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Back onto the client thread before anything touches a screen. */
    private static void deliver(int count, IntConsumer onCount) {
        if (count < 0 || onCount == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> onCount.accept(count));
        }
    }
}
