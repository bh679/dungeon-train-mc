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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Asks the relay whether this player is the first ever to earn an advancement
 * ({@code POST /advancements/first-claim}). The relay records the first clean claimant atomically,
 * so the answer is authoritative across every player and install. Fail-closed: an error, timeout or
 * odd body that outlasts the retries means "not first" — the player is never told a first that isn't.
 *
 * <p>The relay records a claim even when its answer never gets back, and a record is only won once, so
 * giving up early loses the announcement for good (AshSeven's Coffee Table Book: the relay stalled for
 * 11 s, this client stopped listening at 6). A claim with no answer is therefore retried, marked
 * {@code retry}, which lets the relay repeat "first" to the holder it just recorded. Only an unanswered
 * claim retries, so the first answer heard is the only one acted on.</p>
 */
public final class AdvancementFirstClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Pin HTTP/1.1 for local cleartext testing — see BookStatsClient for the rationale.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration CLAIM_TIMEOUT = Duration.ofSeconds(15);
    /** Seconds before each retry. The whole run must stay inside the relay's retry window (10 min). */
    static final long[] RETRY_DELAYS_SECONDS = {5L, 20L, 60L};

    private AdvancementFirstClient() {}

    /** The relay's answer: was this the first, and who holds the record (name may be blank). */
    public record Answer(boolean first, String byName) {}

    /**
     * Off-thread; {@code callback} runs on the HTTP thread with the answer, or not at all once the
     * retries run out. It can run minutes after the earn.
     *
     * @param difficulty the vanilla difficulty partition key the earn happened on
     *                   ({@code DifficultyPartition.keyFor}) — the relay keeps advancement stats per
     *                   difficulty off this claim, since every clean earn sends one
     * @param burrito    the Everything Burrito's recorded play time when this is that earn, else null
     * @param challengeTicks this life's ticks aboard when this is The Challenge's earn, else 0
     */
    public static void claim(UUID player, String name, String advancementId, String difficulty,
                             BurritoClock.Completion burrito, long challengeTicks, Consumer<Answer> callback) {
        try {
            sendClaim(body(player, name, advancementId, difficulty, burrito, challengeTicks), advancementId, 0, callback);
        } catch (Throwable t) {
            LOGGER.info("[DungeonTrain] first-claim for {} failed to start: {}", advancementId, t.toString());
        }
    }

    private static void sendClaim(JsonObject firstBody, String advancementId, int attempt, Consumer<Answer> callback) {
        JsonObject body = attempt == 0 ? firstBody : retryBody(firstBody);
        HttpRequest req = HttpRequest.newBuilder(URI.create(DungeonTrain.relayBaseUrl() + "/advancements/first-claim"))
                .timeout(CLAIM_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    try {
                        if (err != null) {
                            retryOrGiveUp(firstBody, advancementId, attempt, callback, err.toString());
                            return;
                        }
                        if (shouldRetryStatus(resp.statusCode())) {
                            retryOrGiveUp(firstBody, advancementId, attempt, callback, "HTTP " + resp.statusCode());
                            return;
                        }
                        if (resp.statusCode() / 100 != 2) {
                            LOGGER.info("[DungeonTrain] first-claim for {} -> HTTP {}", advancementId, resp.statusCode());
                            return;
                        }
                        Answer parsed = parse(resp.body());
                        if (parsed != null) callback.accept(parsed);
                    } catch (Throwable t) {
                        LOGGER.info("[DungeonTrain] first-claim for {} parse failed: {}", advancementId, t.toString());
                    }
                });
    }

    private static void retryOrGiveUp(JsonObject firstBody, String advancementId, int attempt,
                                      Consumer<Answer> callback, String reason) {
        if (attempt >= RETRY_DELAYS_SECONDS.length) {
            LOGGER.info("[DungeonTrain] first-claim for {} gave up after {} tries: {}", advancementId, attempt + 1, reason);
            return;
        }
        long delay = RETRY_DELAYS_SECONDS[attempt];
        LOGGER.info("[DungeonTrain] first-claim for {} got no answer ({}); retrying in {} s", advancementId, reason, delay);
        CompletableFuture.delayedExecutor(delay, TimeUnit.SECONDS).execute(() -> {
            try {
                sendClaim(firstBody, advancementId, attempt + 1, callback);
            } catch (Throwable t) {
                LOGGER.info("[DungeonTrain] first-claim retry for {} failed to start: {}", advancementId, t.toString());
            }
        });
    }

    /** A 5xx is the relay not answering (a restart, Apache's 503), so the claim may yet land. A 4xx never will. */
    static boolean shouldRetryStatus(int status) {
        return status >= 500;
    }

    /** A copy of the claim's body, marked as a retry. */
    static JsonObject retryBody(JsonObject firstBody) {
        JsonObject body = firstBody.deepCopy();
        body.addProperty("retry", true);
        return body;
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
     * The claim's JSON body. A burrito earn also carries {@code burritoTicks} (ticks of play since first
     * played or the last reset, BurritoClock) and {@code burritoAttempt} — the relay's fastest-burrito board. A
     * Challenge earn carries {@code challengeTicks} (ticks aboard in the life that earned it) — the
     * fastest-challenge board.
     */
    static JsonObject body(UUID player, String name, String advancementId, String difficulty,
                           BurritoClock.Completion burrito, long challengeTicks) {
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
        if (challengeTicks > 0L) {
            body.addProperty("challengeTicks", challengeTicks);
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
