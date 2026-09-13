package games.brennan.dungeontrain.client.credits;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.chat.RelayChatClient;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Transport for {@code POST /<CAP>/credits/edit} — a player renaming, removing (going anonymous)
 * or restoring their own line on the Credits page, in any of its three sections.
 *
 * <p>Carries a uuid, so it is <b>consent-gated</b> and refuses to start without
 * {@link RelayChatClient#canConnect()}. Same posture as every other relay client here otherwise:
 * HTTP/1.1-pinned, never blocking, no-throw at every entry point. The body and the response
 * mapping are pure functions so they can be tested without a relay.</p>
 *
 * <p><b>One identity.</b> The body carries {@code section: "all"}: the relay applies the edit to
 * every card the player is credited on, so a name changed beside one card changes beside all
 * three. It goes to the <b>live</b> pool, where the writers and builders are read from; a dev
 * build whose branch-routed cap differs (its translations live there) posts to that cap too, and
 * the edit is ok when either pool accepted it.</p>
 *
 * <p>The relay's refusals are surfaced as distinct {@link Error}s rather than one "failed",
 * because a player can act on them: {@code NAME_TAKEN} wants a different name, {@code
 * UNSUPPORTED} wants a newer relay, and {@code NOT_YOURS} means the row the page offered them was
 * stale — none of which "try again later" would tell them.</p>
 */
public final class CreditEditClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    static final String PATH = "/credits/edit";

    /** Statuses meaning "this relay predates the endpoint" rather than "this request is bad". */
    private static final Set<Integer> ENDPOINT_MISSING = Set.of(404, 405, 501);

    private CreditEditClient() {}

    /** The three cards of the Credits page — which one an Edit button sits on. The edit itself is for all. */
    public enum Section {
        TRANSLATIONS, WRITERS, BUILDERS;

        /** The relay's wire name. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The wire value asking for every section at once. */
    static final String ALL = "all";

    /** What the player asked for. */
    public enum Action {
        RENAME, REMOVE, RESTORE;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Why an edit did not happen. {@code NONE} on success. */
    public enum Error { NONE, NO_CONSENT, NOT_YOURS, NAME_TAKEN, RATE_LIMITED, UNSUPPORTED, FAILED }

    /** The outcome of one POST; {@code updated} is how many rows a rename touched (0 otherwise). */
    public record Result(boolean ok, Error error, int updated) {
        static Result of(Error error) {
            return new Result(false, error, 0);
        }
    }

    /** The {@code /credits/edit} body — matches the contract in the relay's README. */
    static JsonObject buildPayload(String uuid, Action action, String from, String to) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid == null ? "" : uuid);
        body.addProperty("section", ALL);
        body.addProperty("action", action.wire());
        if (action == Action.RENAME) {
            body.addProperty("from", from == null ? "" : from);
            body.addProperty("to", to == null ? "" : to);
        }
        return body;
    }

    /** Rename this player's credit, on every card, from {@code from} to {@code to}. */
    public static CompletableFuture<Result> rename(String from, String to) {
        return send(Action.RENAME, from, to);
    }

    /** Take this player's name off every card (their lines render as Anonymous, counts kept). */
    public static CompletableFuture<Result> remove() {
        return send(Action.REMOVE, "", "");
    }

    /** Put this player's name back on every card. */
    public static CompletableFuture<Result> restore() {
        return send(Action.RESTORE, "", "");
    }

    /**
     * Ask the relay for one edit. Resolves on the calling thread of the HTTP client — hop to the
     * render thread before touching a screen.
     */
    public static CompletableFuture<Result> send(Action action, String from, String to) {
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null || !RelayChatClient.canConnect()) {
            return CompletableFuture.completedFuture(Result.of(Error.NO_CONSENT));
        }
        String json = buildPayload(uuid.toString().replace("-", ""), action, from, to).toString();
        CompletableFuture<Result> live = post(RelayTarget.live(), json, action);
        if (RelayTarget.dev().equals(RelayTarget.live())) {
            return live;
        }
        // A dev build: its translations live on the branch cap. Either pool accepting is a success;
        // otherwise the live pool's verdict is the one the page can act on.
        return live.thenCombine(post(RelayTarget.dev(), json, action), CreditEditClient::either);
    }

    static Result either(Result live, Result dev) {
        if (live.ok()) return dev.ok() ? new Result(true, Error.NONE, live.updated() + dev.updated()) : live;
        if (dev.ok()) return dev;
        return live.error() == Error.NOT_YOURS && dev.error() != Error.NOT_YOURS ? dev : live;
    }

    private static CompletableFuture<Result> post(String base, String json, Action action) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + PATH))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> interpret(resp.statusCode(), resp.body()))
                .exceptionally(t -> {
                    LOGGER.debug("[DungeonTrain] Credits: {} failed — {}", action, t.toString());
                    return Result.of(Error.FAILED);
                });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Credits: {} could not start — {}", action, t.toString());
            return CompletableFuture.completedFuture(Result.of(Error.FAILED));
        }
    }

    /**
     * Map a response to a verdict. 2xx succeeds; 409 carries the relay's named refusal; 429 is the
     * hourly budget; a missing endpoint is an older relay; everything else failed.
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
        LOGGER.warn("[DungeonTrain] Credits: relay refused the edit (HTTP {}).", status);
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
