package games.brennan.dungeontrain.cheat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Sends one whitelist suggestion to the relay: {@code POST /<CAP>/mods/suggest}. The relay records it
 * as a new suggestion when nobody has suggested that mod yet, or as a backing vote on the existing
 * one — {@link Result#CREATED} vs {@link Result#BACKED}.
 *
 * <p>The request carries an owner proof ({@code name} + {@code serverId}): the relay only accepts a
 * suggestion from a Minecraft account Mojang confirms, never from a bare uuid. Obtaining that proof
 * needs the client's session, so it happens on the client side ({@code ModSuggestProof}); this class
 * only speaks HTTP and never touches client classes.</p>
 *
 * <p>Never fails exceptionally: every failure resolves to a {@link Result} the screen can show.</p>
 */
public final class ModSuggestClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Pin HTTP/1.1 for the same reason the other relay clients do: Java's default HTTP/2 client can't
    // h2c-upgrade over plaintext http://, which breaks local 127.0.0.1 relay testing.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** What happened to a suggestion — one per message the screen can show. */
    public enum Result {
        /** This vote started the suggestion. */
        CREATED,
        /** Somebody had already suggested it; this vote backs theirs. */
        BACKED,
        /** Mojang could not confirm the account (offline/dev account, or the join failed). */
        NOT_PROVEN,
        /** The operator has already approved, rejected or ruled on this mod. */
        ALREADY_DECIDED,
        /** The mod is already on the whitelist (approved since this client last fetched it). */
        ALREADY_LISTED,
        /** Starting a suggestion needs a few words of why. */
        EXPLANATION_REQUIRED,
        /** The comment tripped the relay's hard word lists. */
        COMMENT_REJECTED,
        /** Too many suggestions from this address in a short window. */
        RATE_LIMITED,
        /** Anything else: no network, a relay error, an unexpected answer. */
        FAILED;

        /** A result the player can't fix by rewording and resending. */
        public boolean isFinal() {
            return this == CREATED || this == BACKED || this == ALREADY_DECIDED || this == ALREADY_LISTED;
        }
    }

    private ModSuggestClient() {}

    /**
     * POST the suggestion. {@code uuid}/{@code name}/{@code serverId} are the owner proof; {@code
     * comment} is required (the relay refuses an empty one). No-throw.
     */
    public static CompletableFuture<Result> suggest(String baseUrl, String uuid, String name, String serverId,
                                                    String modId, String comment) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("uuid", uuid == null ? "" : uuid);
            body.addProperty("name", name == null ? "" : name);
            body.addProperty("serverId", serverId == null ? "" : serverId);
            body.addProperty("modId", modId == null ? "" : modId);
            body.addProperty("comment", comment == null ? "" : comment);
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/mods/suggest"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .handle((resp, err) -> {
                        if (err != null) {
                            LOGGER.info("[DungeonTrain] mod suggestion for {} failed: {}", modId, err.toString());
                            return Result.FAILED;
                        }
                        Result r = resultOf(resp.statusCode(), resp.body());
                        LOGGER.info("[DungeonTrain] mod suggestion for {} -> HTTP {} {}", modId, resp.statusCode(), r);
                        return r;
                    });
        } catch (Throwable t) {
            LOGGER.info("[DungeonTrain] mod suggestion for {} could not start: {}", modId, t.toString());
            return CompletableFuture.completedFuture(Result.FAILED);
        }
    }

    /**
     * Pure: map the relay's answer to a {@link Result}. A 200 reads {@code created}; a refusal reads
     * its {@code error} code. Anything unrecognised is {@link Result#FAILED}. Package-visible for tests.
     */
    static Result resultOf(int status, String body) {
        JsonObject o = parseObject(body);
        if (status / 100 == 2) {
            if (o == null || !o.has("ok") || !o.get("ok").isJsonPrimitive() || !o.get("ok").getAsBoolean()) {
                return Result.FAILED;
            }
            boolean created = o.has("created") && o.get("created").isJsonPrimitive()
                && o.getAsJsonPrimitive("created").isBoolean() && o.get("created").getAsBoolean();
            return created ? Result.CREATED : Result.BACKED;
        }
        if (status == 429) return Result.RATE_LIMITED;
        String error = o != null && o.has("error") && o.get("error").isJsonPrimitive()
            ? o.get("error").getAsString() : "";
        return switch (error) {
            case "not_proven" -> Result.NOT_PROVEN;
            case "already_decided" -> Result.ALREADY_DECIDED;
            case "already_listed" -> Result.ALREADY_LISTED;
            case "explanation_required" -> Result.EXPLANATION_REQUIRED;
            case "comment_rejected" -> Result.COMMENT_REJECTED;
            case "rate_limited" -> Result.RATE_LIMITED;
            default -> Result.FAILED;
        };
    }

    private static JsonObject parseObject(String body) {
        try {
            JsonElement e = JsonParser.parseString(body == null ? "" : body);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception ex) {
            return null;
        }
    }
}
