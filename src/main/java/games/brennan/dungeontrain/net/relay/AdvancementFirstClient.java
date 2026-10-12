package games.brennan.dungeontrain.net.relay;

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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Asks the relay whether this player is the first ever to earn an advancement
 * ({@code POST /advancements/first-claim}). The relay records the first clean claimant atomically,
 * so the answer is authoritative across every player and install. Fail-closed: any error, timeout
 * or odd body means "not first" — the player is never told a first that isn't.
 */
public final class AdvancementFirstClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Pin HTTP/1.1 for local cleartext testing — see BookStatsClient for the rationale.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);

    private AdvancementFirstClient() {}

    /** The relay's answer: was this the first, and who holds the record (name may be blank). */
    public record Answer(boolean first, String byName) {}

    /** Off-thread; {@code callback} runs on the HTTP thread with the answer, or not at all on failure. */
    public static void claim(UUID player, String name, String advancementId, Consumer<Answer> callback) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("id", advancementId);
            body.addProperty("uuid", player.toString().replace("-", ""));
            body.addProperty("name", name);
            body.addProperty("modVersion", VersionInfo.VERSION);
            HttpRequest req = HttpRequest.newBuilder(URI.create(DungeonTrain.relayBaseUrl() + "/advancements/first-claim"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] first-claim failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] first-claim -> HTTP {}", resp.statusCode());
                                return;
                            }
                            Answer parsed = parse(resp.body());
                            if (parsed != null) callback.accept(parsed);
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] first-claim parse failed: {}", t.toString());
                        }
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] first-claim request failed to start: {}", t.toString());
        }
    }

    /** {@code {ok:true, first:bool, by:{name}}} → an answer; anything else → null (treated as not first). */
    static Answer parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        if (!o.has("ok") || !o.get("ok").getAsBoolean()) return null;
        if (!o.has("first") || !o.get("first").isJsonPrimitive()) return null;
        String by = "";
        if (o.has("by") && o.get("by").isJsonObject()) {
            JsonObject b = o.getAsJsonObject("by");
            if (b.has("name") && b.get("name").isJsonPrimitive()) by = b.get("name").getAsString();
        }
        return new Answer(o.get("first").getAsBoolean(), by);
    }
}
