package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.client.chat.RelayChatClient;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Tells the relay that a translator has marked machine-translated strings <b>good as is</b>
 * ({@code POST /<CAP>/translations/dismiss}).
 *
 * <p>Nothing acts on it at the other end — it is not a submission and cannot become one. The
 * authority is {@link TranslationDismissals}' file on this disk; this exists so the review side
 * can see which lines a speaker of the language has read and let stand, which a queue of
 * unreviewed machine translation cannot otherwise show.</p>
 *
 * <p>Because of that, and unlike {@link TranslationSubmitClient}, there is <b>no outbox and no
 * retry</b>: a dismissal that never reaches the relay costs the player nothing, and a durable
 * queue for a record nobody reads back would be machinery in exchange for nothing. Carries a
 * uuid, so it is consent-gated like every other uuid-bearing call.</p>
 */
public final class TranslationDismissClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private TranslationDismissClient() {}

    /**
     * The {@code /translations/dismiss} body. Pure, so the shape can be tested without a relay —
     * {@link TranslationSubmitClient#buildPayload} is the model.
     *
     * <p>No {@code value}: a dismissal is a judgement about text the mod already shipped, so there
     * is no player-authored string in it at all. {@code shipped} is what they read and approved
     * of, which is the whole content of the claim.</p>
     */
    static JsonObject buildPayload(String uuid, String locale, List<TranslationUnit> units) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid == null ? "" : uuid);
        body.addProperty("locale", locale == null ? "" : locale);
        body.addProperty("modVersion", VersionInfo.VERSION);
        JsonArray arr = new JsonArray();
        if (units != null) {
            for (TranslationUnit unit : units) {
                JsonObject u = new JsonObject();
                u.addProperty("type", unit.type() == TranslationUnit.Type.BOOK ? "book" : "lang");
                u.addProperty("namespace", unit.namespace() == null ? "" : unit.namespace());
                u.addProperty("id", unit.id());
                u.addProperty("source", unit.source() == null ? "" : unit.source());
                u.addProperty("shipped", unit.shipped() == null ? "" : unit.shipped());
                arr.add(u);
            }
        }
        body.add("units", arr);
        return body;
    }

    /** Report one string as good as is. Never throws, never blocks, never retries. */
    public static void send(String locale, TranslationUnit unit) {
        if (unit == null) {
            return;
        }
        send(locale, List.of(unit));
    }

    /** Report several at once — the same one call the editor would otherwise make in a loop. */
    public static void send(String locale, List<TranslationUnit> units) {
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null || units == null || units.isEmpty() || !RelayChatClient.canConnect()) {
            return;
        }
        try {
            String json = buildPayload(uuid.toString().replace("-", ""), locale, units).toString();
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create(DungeonTrain.relayBaseUrl() + "/translations/dismiss"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    if (err != null || resp == null || resp.statusCode() / 100 != 2) {
                        // Includes a relay older than the endpoint. Debug only: the player's own
                        // file already holds the decision, and nothing here is recoverable work.
                        LOGGER.debug("[DungeonTrain] Translations: dismissal not recorded — {}",
                            err != null ? err.toString()
                                : "HTTP " + (resp == null ? "?" : resp.statusCode()));
                    }
                });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Translations: dismissal could not be sent — {}", t.toString());
        }
    }
}
