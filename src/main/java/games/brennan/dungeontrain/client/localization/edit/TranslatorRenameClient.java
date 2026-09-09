package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.chat.RelayChatClient;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Transport for {@code POST /<CAP>/translations/rename} — a translator changing the name they are
 * credited under, from the Credits page.
 *
 * <p>Carries a uuid, so it is <b>consent-gated</b> like {@link TranslationSubmissionsClient} and
 * refuses to start without {@link RelayChatClient#canConnect()}. Same posture as every other relay
 * client here otherwise: HTTP/1.1-pinned, never blocking, no-throw at every entry point. The body
 * and the response mapping are pure functions so they can be tested without a relay.</p>
 *
 * <p>The relay's refusals are surfaced as distinct {@link Error}s rather than one "failed",
 * because a translator can act on them: {@code NAME_TAKEN} wants a different name, {@code
 * UNSUPPORTED} wants a newer relay, and {@code NOT_YOURS} means the name the page offered them was
 * stale — none of which "try again later" would tell them.</p>
 */
public final class TranslatorRenameClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** Statuses meaning "this relay predates the endpoint" rather than "this request is bad". */
    private static final Set<Integer> ENDPOINT_MISSING = Set.of(404, 405, 501);

    private TranslatorRenameClient() {}

    /** Why a rename did not happen. {@code NONE} on success. */
    public enum Error { NONE, NO_CONSENT, NOT_YOURS, NAME_TAKEN, RATE_LIMITED, UNSUPPORTED, FAILED }

    /** The outcome of one POST; {@code updated} is how many of the player's rows were renamed. */
    public record Result(boolean ok, Error error, int updated) {
        static Result of(Error error) {
            return new Result(false, error, 0);
        }
    }

    /** The {@code /translations/rename} body — matches the contract in the relay's README. */
    static JsonObject buildPayload(String uuid, String from, String to) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid == null ? "" : uuid);
        body.addProperty("from", from == null ? "" : from);
        body.addProperty("to", to == null ? "" : to);
        return body;
    }

    /**
     * Ask the relay to rename this player's credit from {@code from} to {@code to}. Resolves on
     * the calling thread of the HTTP client — hop to the render thread before touching a screen.
     */
    public static CompletableFuture<Result> send(String from, String to) {
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null || !RelayChatClient.canConnect()) {
            return CompletableFuture.completedFuture(Result.of(Error.NO_CONSENT));
        }
        try {
            String json = buildPayload(uuid.toString().replace("-", ""), from, to).toString();
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create(DungeonTrain.relayBaseUrl() + "/translations/rename"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> interpret(resp.statusCode(), resp.body()))
                .exceptionally(t -> {
                    LOGGER.debug("[DungeonTrain] Translations: rename failed — {}", t.toString());
                    return Result.of(Error.FAILED);
                });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Translations: rename could not start — {}", t.toString());
            return CompletableFuture.completedFuture(Result.of(Error.FAILED));
        }
    }

    /**
     * Map a response to a verdict. 2xx succeeds; 409 carries the relay's named refusal; 429 is the
     * shared hourly budget; a missing endpoint is an older relay; everything else failed.
     */
    static Result interpret(int status, String body) {
        if (status / 100 == 2) {
            return new Result(true, Error.NONE, intField(body, "updated"));
        }
        if (status == 409) {
            String error = stringField(body, "error");
            return Result.of("name_taken".equals(error) ? Error.NAME_TAKEN
                : "not_yours".equals(error) ? Error.NOT_YOURS : Error.FAILED);
        }
        if (status == 429) {
            return Result.of(Error.RATE_LIMITED);
        }
        if (ENDPOINT_MISSING.contains(status)) {
            return Result.of(Error.UNSUPPORTED);
        }
        LOGGER.warn("[DungeonTrain] Translations: relay refused the rename (HTTP {}).", status);
        return Result.of(Error.FAILED);
    }

    private static int intField(String body, String field) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject() && root.getAsJsonObject().has(field)) {
                return root.getAsJsonObject().get(field).getAsInt();
            }
        } catch (Exception ignored) {
            // A 2xx with an unreadable body still means the relay took it.
        }
        return 0;
    }

    private static String stringField(String body, String field) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject() && root.getAsJsonObject().has(field)) {
                return root.getAsJsonObject().get(field).getAsString();
            }
        } catch (Exception ignored) {
            // Fall through: an unreadable refusal is still a refusal.
        }
        return "";
    }
}
