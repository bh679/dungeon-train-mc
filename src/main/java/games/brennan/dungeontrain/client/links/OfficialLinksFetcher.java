package games.brennan.dungeontrain.client.links;

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
import java.util.HashMap;
import java.util.Map;

/**
 * Off-thread reader for the relay's {@code GET /<CAP>/links} endpoint — the official outbound
 * links overlay for {@link OfficialLinks}. Mirrors
 * {@link games.brennan.dungeontrain.net.relay.BookStatsClient}'s fire-and-forget GET pattern
 * (own {@link HttpClient}, no-throw, best-effort): any failure just leaves the baked fallbacks
 * in place and flags {@link OfficialLinks#markFailed()} so the next title screen retries.
 *
 * <p>The request is anonymous — no uuid, session, or query params of any kind — so it runs
 * regardless of the network-consent setting (a deliberate product decision; see the feature's
 * Gate 1 plan).</p>
 */
public final class OfficialLinksFetcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Pin HTTP/1.1: the relay is a cleartext-capable Node server; Java's default HTTP/2 client
    // can't h2c-upgrade over plaintext http:// (breaks local 127.0.0.1 testing). Harmless in
    // prod — Apache proxies HTTP/1.1 to the origin regardless.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private OfficialLinksFetcher() {}

    /** Fetch the links overlay off-thread; results land in {@link OfficialLinks}. No-throw. */
    static void fetchAsync() {
        try {
            String url = DungeonTrain.relayBaseUrl() + "/links";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] links fetch failed: {}", err.toString());
                                OfficialLinks.markFailed();
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] links fetch -> HTTP {}", resp.statusCode());
                                OfficialLinks.markFailed();
                                return;
                            }
                            Map<String, String> links = parse(resp.body());
                            if (links != null) {
                                OfficialLinks.accept(links);
                                LOGGER.debug("[DungeonTrain] official links updated from relay ({} keys)", links.size());
                            } else {
                                OfficialLinks.markFailed();
                            }
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] links parse failed: {}", t.toString());
                            OfficialLinks.markFailed();
                        }
                    });
        } catch (Throwable t) {
            // Building the request failed synchronously — swallow; baked fallbacks stay in force.
            LOGGER.debug("[DungeonTrain] links request failed to start: {}", t.toString());
            OfficialLinks.markFailed();
        }
    }

    /** Parse {@code {ok:true, links:{...}}} into a raw key→url map, or null when malformed. */
    static Map<String, String> parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        if (!o.has("ok") || !o.get("ok").getAsBoolean()) return null;
        if (!o.has("links") || !o.get("links").isJsonObject()) return null;
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("links").entrySet()) {
            if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isString()) {
                out.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return out;
    }
}
