package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VersionInfo;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Transport for {@code POST /<CAP>/translations/submit}.
 *
 * <p>Same posture as every other relay client here: its own HTTP/1.1-pinned client (Java's
 * default HTTP/2 cannot h2c-upgrade over plaintext, which is what local 127.0.0.1 relay testing
 * needs), never blocking, and no-throw at every entry point.</p>
 *
 * <p>The body shape is a pure function of the arguments so it can be unit-tested without a
 * running relay, the way {@code SharedBookReporter.buildPayload} is.</p>
 */
public final class TranslationSubmitClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private TranslationSubmitClient() {}

    /**
     * The outcome of one POST. {@code retryable} is what the outbox keys on: a 4xx is a poison
     * payload the relay will never accept, so retrying it forever would block the queue behind it.
     */
    public record SendResult(boolean ok, boolean retryable, int stored) {
        static final SendResult RETRY = new SendResult(false, true, 0);
        static final SendResult POISON = new SendResult(false, false, 0);
    }

    /** One unit as the relay's {@code normaliseUnit} expects it. */
    public record Unit(String type, String namespace, String id, String source, String value) {}

    /**
     * The {@code /translations/submit} body. Package-private so the shape can be tested without a
     * relay; matches the contract in the relay's README.
     */
    static JsonObject buildPayload(String uuid, String translator, String locale, List<Unit> units) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid == null ? "" : uuid);
        body.addProperty("translator", translator == null ? "" : translator);
        body.addProperty("locale", locale == null ? "" : locale);
        body.addProperty("modVersion", VersionInfo.VERSION);
        JsonArray arr = new JsonArray();
        if (units != null) {
            for (Unit unit : units) {
                JsonObject u = new JsonObject();
                u.addProperty("type", unit.type());
                u.addProperty("namespace", unit.namespace() == null ? "" : unit.namespace());
                u.addProperty("id", unit.id());
                u.addProperty("source", unit.source() == null ? "" : unit.source());
                u.addProperty("value", unit.value());
                arr.add(u);
            }
        }
        body.add("units", arr);
        return body;
    }

    /** POST a pre-built body. Never throws; failures come back as a non-ok {@link SendResult}. */
    public static CompletableFuture<SendResult> send(String json) {
        try {
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create(DungeonTrain.relayBaseUrl() + "/translations/submit"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(TranslationSubmitClient::interpret)
                .exceptionally(t -> {
                    LOGGER.debug("[DungeonTrain] Translations: submit failed — {}", t.toString());
                    return SendResult.RETRY;
                });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Translations: submit could not start — {}", t.toString());
            return CompletableFuture.completedFuture(SendResult.RETRY);
        }
    }

    /**
     * Statuses meaning "this relay predates the endpoint" rather than "this payload is bad".
     *
     * <p>The mod and the relay deploy independently, so a client can easily reach a relay that
     * has not been updated yet. Treating that as poison would silently bin a translator's work
     * for the gap between the two deploys. {@code RelayOutbox} draws the same distinction with
     * its {@code BATCH_FALLBACK_STATUSES} for exactly this reason.</p>
     */
    private static final java.util.Set<Integer> ENDPOINT_MISSING = java.util.Set.of(404, 405, 501);

    /**
     * Map a response to a verdict. 2xx succeeds (including the relay's dedupe reply, which is a
     * 200 with {@code stored: 0} — a retry of already-stored work must not look like a failure).
     * 408/429/5xx are transient, as is an endpoint the relay does not have yet; every other 4xx
     * is a payload it will never accept.
     */
    static SendResult interpret(HttpResponse<String> resp) {
        int status = resp.statusCode();
        if (status / 100 == 2) {
            return new SendResult(true, false, storedFrom(resp.body()));
        }
        boolean retryable = status == 408 || status == 429 || status >= 500
            || ENDPOINT_MISSING.contains(status);
        if (!retryable) {
            LOGGER.warn("[DungeonTrain] Translations: relay rejected the submission (HTTP {}); "
                + "dropping it rather than retrying forever.", status);
        }
        return retryable ? SendResult.RETRY : SendResult.POISON;
    }

    private static int storedFrom(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject() && root.getAsJsonObject().has("stored")) {
                return root.getAsJsonObject().get("stored").getAsInt();
            }
        } catch (Exception ignored) {
            // A 2xx with an unreadable body still means the relay took it.
        }
        return 0;
    }
}
