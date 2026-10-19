package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.BurritoClock;
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

    /**
     * Off-thread; {@code callback} runs on the HTTP thread with the answer, or not at all on failure.
     *
     * @param difficulty the vanilla difficulty partition key the earn happened on
     *                   ({@code DifficultyPartition.keyFor}) — the relay keeps advancement stats per
     *                   difficulty off this claim, since every clean earn sends one
     * @param burrito    the Everything Burrito's recorded play time when this is that earn, else null
     */
    public static void claim(UUID player, String name, String advancementId, String difficulty,
                             BurritoClock.Completion burrito, Consumer<Answer> callback) {
        try {
            JsonObject body = body(player, name, advancementId, difficulty, burrito);
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

    /**
     * Tell the relay this earn was made in a Free Play run ({@code POST /advancements/freeplay-earn}),
     * so it never infers a first-ever record from it. Fire-and-forget: the answer is not read, and an
     * older relay's 404 or any failure is simply dropped.
     */
    public static void reportFreePlay(UUID player, String advancementId) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(DungeonTrain.relayBaseUrl() + "/advancements/freeplay-earn"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(freePlayBody(player, advancementId).toString(), StandardCharsets.UTF_8))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((resp, err) -> {
                        if (err != null) LOGGER.debug("[DungeonTrain] freeplay-earn failed: {}", err.toString());
                        else if (resp.statusCode() / 100 != 2) LOGGER.debug("[DungeonTrain] freeplay-earn -> HTTP {}", resp.statusCode());
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] freeplay-earn request failed to start: {}", t.toString());
        }
    }

    /** The Free Play report's JSON body. */
    static JsonObject freePlayBody(UUID player, String advancementId) {
        JsonObject body = new JsonObject();
        body.addProperty("id", advancementId);
        body.addProperty("uuid", player.toString().replace("-", ""));
        body.addProperty("modVersion", VersionInfo.VERSION);
        return body;
    }

    /**
     * The claim's JSON body. A burrito earn also carries {@code burritoTicks} (ticks aboard since first
     * played or the last reset) and {@code burritoAttempt} — the relay's fastest-burrito board.
     */
    static JsonObject body(UUID player, String name, String advancementId, String difficulty,
                           BurritoClock.Completion burrito) {
        JsonObject body = new JsonObject();
        body.addProperty("id", advancementId);
        body.addProperty("uuid", player.toString().replace("-", ""));
        body.addProperty("name", name);
        body.addProperty("modVersion", VersionInfo.VERSION);
        if (difficulty != null && !difficulty.isEmpty()) {
            body.addProperty("difficulty", difficulty);
        }
        if (burrito != null) {
            body.addProperty("burritoTicks", burrito.ticks());
            body.addProperty("burritoAttempt", burrito.attempt());
        }
        return body;
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
