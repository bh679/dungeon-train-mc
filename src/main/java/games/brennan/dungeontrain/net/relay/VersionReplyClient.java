package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Hands the answer to a Developer {@code /version} ping back to the relay, which delivers it privately
 * to Brennan (ephemeral Discord reply, DM, or the admin page that asked):
 *
 * <pre>
 * POST &lt;relayBase&gt;/dev/version-reply
 *   { "reqId": "…", "uuid": "…", "name": "Steve", "status": "ok|kid|offline|timeout|left", "text": "…" }
 * </pre>
 *
 * <p>Fire-and-forget like {@link DebugGrantClient}: the request expires on the relay within minutes,
 * so a failed POST is logged and dropped rather than queued in {@link RelayOutbox}.</p>
 */
public final class VersionReplyClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Pin HTTP/1.1 for local cleartext testing — see BookStatsClient for the rationale.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** Outcome of a request, as the relay reads it. */
    public enum Status { OK, KID, OFFLINE, TIMEOUT, LEFT }

    private VersionReplyClient() {}

    /** POST the reply off-thread. No-throw. */
    public static void post(String reqId, UUID uuid, String name, Status status, String text) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(DungeonTrain.relayBaseUrl() + "/dev/version-reply"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(reqId, uuid, name, status, text), StandardCharsets.UTF_8))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((resp, err) -> {
                        if (err != null) {
                            LOGGER.warn("[DungeonTrain] version reply failed: {}", err.toString());
                        } else if (resp.statusCode() / 100 != 2) {
                            LOGGER.warn("[DungeonTrain] version reply -> HTTP {}", resp.statusCode());
                        }
                    });
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] version reply failed to start: {}", t.toString());
        }
    }

    /** The wire body. Package-private so the contract with the relay can be pinned in a test. */
    static String body(String reqId, UUID uuid, String name, Status status, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("reqId", reqId);
        o.addProperty("uuid", uuid.toString());
        o.addProperty("name", name == null ? "" : name);
        o.addProperty("status", status.name().toLowerCase(java.util.Locale.ROOT));
        o.addProperty("text", text == null ? "" : text);
        return o.toString();
    }
}
