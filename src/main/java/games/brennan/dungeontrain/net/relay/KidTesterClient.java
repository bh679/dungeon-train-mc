package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Off-thread reader for the relay's {@code GET /<CAP>/books/kidtester?uuid=} endpoint — "is this
 * player on the kid-safe-tester roster?" ({@code kidtesters.js}). Asked once per player when their
 * network consent lands, and spent on one decision only: whether to DRAW the red "Remove for kids"
 * control on the book vote page.
 *
 * <p>Mirrors {@link BookStatsClient}'s fire-and-forget GET pattern — its own {@link HttpClient} with
 * the same HTTP/1.1 pin, no-throw, best-effort. The callback runs on the HTTP completion thread, so
 * the caller hops back to the server thread before touching game state.</p>
 *
 * <p><b>A failed fetch calls back with {@code false}</b>, unlike {@link BookStatsClient}, which simply
 * stays silent. The two are answering different kinds of question: a missing stats line is a missing
 * flourish, while a missing answer here has to resolve to "not a tester" for the fail-closed rule in
 * {@link games.brennan.dungeontrain.event.KidTesterMirror} to mean anything. Not calling back at all
 * would leave the mirror on a stale mark from a previous login on the same server.</p>
 */
public final class KidTesterClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Pin HTTP/1.1 for the same reason BookStatsClient does: Java's default HTTP/2 client cannot
    // h2c-upgrade over plaintext http://, which breaks local 127.0.0.1 testing.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private KidTesterClient() {}

    /**
     * Ask the relay whether {@code playerId} is a kid-safe tester and hand the answer to
     * {@code callback}. Always calls back exactly once — {@code false} on any failure, timeout,
     * non-2xx or malformed body.
     */
    public static void fetch(UUID playerId, Consumer<Boolean> callback) {
        if (playerId == null || callback == null) return;
        try {
            String url = DungeonTrain.relayBaseUrl()
                    + "/books/kidtester?uuid=" + playerId.toString().replace("-", "");
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        boolean tester = false;
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] kid-tester fetch failed: {}", err.toString());
                            } else if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] kid-tester fetch -> HTTP {}", resp.statusCode());
                            } else {
                                tester = parse(resp.body());
                            }
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] kid-tester parse failed: {}", t.toString());
                        }
                        try {
                            callback.accept(tester);
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] kid-tester callback failed: {}", t.toString());
                        }
                    });
        } catch (Throwable t) {
            // Building the request failed synchronously — answer "not a tester" rather than nothing.
            LOGGER.debug("[DungeonTrain] kid-tester request failed to start: {}", t.toString());
            try {
                callback.accept(false);
            } catch (Throwable ignored) {
                // nothing left to do
            }
        }
    }

    /** {@code true} only for a well-formed {@code {"ok":true,"tester":true}} body. */
    static boolean parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return false;
        JsonObject o = root.getAsJsonObject();
        if (!o.has("ok") || !o.get("ok").getAsBoolean()) return false;
        return o.has("tester") && o.get("tester").isJsonPrimitive() && o.get("tester").getAsBoolean();
    }
}
