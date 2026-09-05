package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.debug.DebugAccessGrants;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Reads one player's debug-panel access grant from the relay, which is the source of truth for who
 * may open the F3+4 panel:
 *
 * <pre>
 * GET &lt;relayBase&gt;/debug-grants?uuid=&lt;mc-uuid&gt;
 *   200 { "ok": true, "grant": { "expiresAtMs": 1790000000000, "source": "discord-thread" } }
 *   200 { "ok": true, "grant": null }
 * </pre>
 *
 * <p>Follows {@link DonationSummaryClient}'s fire-and-forget shape (own {@link HttpClient},
 * HTTP/1.1 pinned so a local cleartext mock relay works, every failure swallowed and logged at
 * debug). The critical detail is what <em>doesn't</em> happen on failure: the callback fires only
 * for a well-formed 2xx body, so a relay outage leaves the server's cached grant exactly as it was.
 * Only the relay answering "no grant" revokes access — never the relay failing to answer.</p>
 */
public final class DebugGrantClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Pin HTTP/1.1 for local cleartext testing — see BookStatsClient for the rationale.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private DebugGrantClient() {}

    /**
     * Fetch {@code player}'s grant off-thread.
     *
     * <p>{@code callback} runs on the HTTP completion thread and is invoked <b>only</b> on a
     * well-formed {@code ok} response — with the relay's grant, or {@code null} for "no grant,
     * revoke any cached one". The caller must hop back to the server thread before touching game
     * state. No-throw.</p>
     */
    public static void fetch(UUID player, Consumer<DebugAccessGrants.Grant> callback) {
        try {
            String url = DungeonTrain.relayBaseUrl() + "/debug-grants?uuid="
                + URLEncoder.encode(player.toString(), StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] debug-grant fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] debug-grant fetch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            Response parsed = parse(resp.body());
                            if (parsed != null) callback.accept(parsed.grant());
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] debug-grant parse failed: {}", t.toString());
                        }
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] debug-grant request failed to start: {}", t.toString());
        }
    }

    /**
     * A well-formed relay answer. Distinct from {@code null}, which means "no usable answer" —
     * {@code grant == null} inside a Response is the relay positively saying this player has none.
     */
    record Response(DebugAccessGrants.Grant grant) {}

    /**
     * Parse the relay body, or null when it isn't a well-formed {@code ok} response.
     * Package-private so the wire shape can be pinned in a test — this payload is the contract the
     * relay and every shipped jar share.
     */
    static Response parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        if (!o.has("ok") || !o.get("ok").getAsBoolean()) return null;

        if (!o.has("grant") || o.get("grant").isJsonNull()) {
            return new Response(null);
        }
        if (!o.get("grant").isJsonObject()) return null;
        JsonObject g = o.getAsJsonObject("grant");
        // A grant with no expiry field is nonsense rather than a "forever" grant — forever is an
        // explicit 0, so a missing field means we misread the payload and should change nothing.
        if (!g.has("expiresAtMs") || g.get("expiresAtMs").isJsonNull()) return null;
        long expiresAtMs = g.get("expiresAtMs").getAsLong();
        String source = g.has("source") && !g.get("source").isJsonNull()
            ? g.get("source").getAsString() : "";
        return new Response(new DebugAccessGrants.Grant(expiresAtMs, source));
    }
}
