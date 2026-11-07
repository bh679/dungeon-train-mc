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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Claims a rare vault find with the relay ({@code POST /vault-finds/claim}), which numbers it per item:
 * 1 is the first ever. Fail-closed like {@link AdvancementFirstClient}: an error, timeout or odd body
 * that outlasts the retries means no answer, so no first and no number, never a guessed one.
 *
 * <p>Retries resend the same body. The claim id it carries makes a retry safe: the relay hands a
 * claim id it already recorded its stored answer, so a lost answer is recovered without counting the
 * find twice.</p>
 */
public final class VaultFindClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Pin HTTP/1.1 for local cleartext testing — see BookStatsClient for the rationale.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Duration CLAIM_TIMEOUT = Duration.ofSeconds(15);
    /** Seconds before each retry, as {@link AdvancementFirstClient#RETRY_DELAYS_SECONDS}. */
    static final long[] RETRY_DELAYS_SECONDS = AdvancementFirstClient.RETRY_DELAYS_SECONDS;

    private VaultFindClient() {}

    /** The relay's answer: is this the first ever, and its number for that item (1-based). */
    public record Answer(boolean first, int serial) {}

    /** Off-thread; {@code callback} runs on the HTTP thread with the answer, or not at all. */
    public static void claim(UUID claimId, UUID player, String name, String itemId, String vault,
                             Consumer<Answer> callback) {
        try {
            send(body(claimId, player, name, itemId, vault), itemId, 0, callback);
        } catch (Throwable t) {
            LOGGER.info("[DungeonTrain] vault-find claim for {} failed to start: {}", itemId, t.toString());
        }
    }

    private static void send(JsonObject body, String itemId, int attempt, Consumer<Answer> callback) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(DungeonTrain.relayBaseUrl() + "/vault-finds/claim"))
                .timeout(CLAIM_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    try {
                        if (err != null) {
                            retryOrGiveUp(body, itemId, attempt, callback, err.toString());
                            return;
                        }
                        if (AdvancementFirstClient.shouldRetryStatus(resp.statusCode())) {
                            retryOrGiveUp(body, itemId, attempt, callback, "HTTP " + resp.statusCode());
                            return;
                        }
                        if (resp.statusCode() / 100 != 2) {
                            LOGGER.info("[DungeonTrain] vault-find claim for {} -> HTTP {}", itemId, resp.statusCode());
                            return;
                        }
                        Answer parsed = parse(resp.body());
                        if (parsed != null) callback.accept(parsed);
                    } catch (Throwable t) {
                        LOGGER.info("[DungeonTrain] vault-find claim for {} parse failed: {}", itemId, t.toString());
                    }
                });
    }

    private static void retryOrGiveUp(JsonObject body, String itemId, int attempt, Consumer<Answer> callback,
                                      String reason) {
        if (attempt >= RETRY_DELAYS_SECONDS.length) {
            LOGGER.info("[DungeonTrain] vault-find claim for {} gave up after {} tries: {}", itemId, attempt + 1, reason);
            return;
        }
        long delay = RETRY_DELAYS_SECONDS[attempt];
        LOGGER.info("[DungeonTrain] vault-find claim for {} got no answer ({}); retrying in {} s", itemId, reason, delay);
        CompletableFuture.delayedExecutor(delay, TimeUnit.SECONDS).execute(() -> {
            try {
                send(body, itemId, attempt + 1, callback);
            } catch (Throwable t) {
                LOGGER.info("[DungeonTrain] vault-find retry for {} failed to start: {}", itemId, t.toString());
            }
        });
    }

    /** The claim's JSON body; uuids go bare, as every relay claim sends them. */
    static JsonObject body(UUID claimId, UUID player, String name, String itemId, String vault) {
        JsonObject body = new JsonObject();
        body.addProperty("claimId", bare(claimId));
        body.addProperty("itemId", itemId);
        body.addProperty("uuid", bare(player));
        body.addProperty("name", name);
        body.addProperty("vault", vault);
        body.addProperty("modVersion", VersionInfo.VERSION);
        return body;
    }

    private static String bare(UUID id) {
        return id.toString().replace("-", "");
    }

    /** {@code {ok:true, first:bool, serial:int>0}} → an answer; anything else → null (no first, no number). */
    static Answer parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        if (!o.has("ok") || !o.get("ok").isJsonPrimitive() || !o.get("ok").getAsBoolean()) return null;
        if (!o.has("first") || !o.get("first").isJsonPrimitive()) return null;
        if (!o.has("serial") || !o.get("serial").isJsonPrimitive()) return null;
        int serial = o.get("serial").getAsInt();
        if (serial < 1) return null;
        return new Answer(o.get("first").getAsBoolean(), serial);
    }
}
