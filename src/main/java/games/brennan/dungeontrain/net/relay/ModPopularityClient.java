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
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Fetches "how many Dungeon Train players run each mod" from the relay, used to order the death
 * screen's Mod Recommendations grid.
 *
 * <p>Read-only, anonymous and consent-free by design: the request sends nothing about the player
 * — not their name, not their mod list — it only asks for the public aggregate the relay already
 * derives from world-info telemetry. The numbers are never displayed, only sorted by, so a failed
 * or slow fetch simply leaves the grid in its alphabetical fallback order.</p>
 *
 * <p>Mirrors {@link DonationSummaryClient}: same HTTP/1.1 pin (for local cleartext testing), same
 * timeouts, no-throw throughout, and the callback fires on the HTTP completion thread — the caller
 * hops back to the client thread before touching screen state.</p>
 */
public final class ModPopularityClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private ModPopularityClient() {}

    /**
     * Fetch {@code modId → players running it} off-thread and hand it to {@code callback}.
     * No-throw: a failed / slow / malformed / non-2xx fetch never calls back.
     */
    public static void fetch(Consumer<Map<String, Integer>> callback) {
        try {
            String url = DungeonTrain.relayBaseUrl() + "/mods/popular";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] mod popularity fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] mod popularity fetch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            Map<String, Integer> parsed = parse(resp.body());
                            if (parsed != null) callback.accept(parsed);
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] mod popularity parse failed: {}", t.toString());
                        }
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] mod popularity request failed to start: {}", t.toString());
        }
    }

    /** Parse {@code {ok:true, mods:[{modId, players}]}} into a map, or null when malformed. */
    static Map<String, Integer> parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        if (!o.has("ok") || !o.get("ok").getAsBoolean()) return null;
        if (!o.has("mods") || !o.get("mods").isJsonArray()) return null;

        Map<String, Integer> out = new HashMap<>();
        for (JsonElement el : o.getAsJsonArray("mods")) {
            if (!el.isJsonObject()) continue;
            JsonObject e = el.getAsJsonObject();
            if (!e.has("modId") || !e.get("modId").isJsonPrimitive()) continue;
            String modId = e.get("modId").getAsString();
            if (modId.isBlank()) continue;
            int players = e.has("players") && e.get("players").isJsonPrimitive()
                    ? e.get("players").getAsInt() : 0;
            out.put(modId, players);
        }
        return Map.copyOf(out);
    }
}
