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
 * <p>Which relay pool the edit goes to follows where that section's credits are <i>read</i> from:
 * translations live on this build's branch-routed cap ({@code /translations/*}), writers and
 * builders on the live pool ({@code RelayWriters}, {@code RelayTemplateBuilders}) — an edit made
 * against the wrong pool would report {@code NOT_YOURS} and change nothing the page shows.</p>
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

    /** The three cards of the Credits page, by the relay's name for each. */
    public enum Section {
        TRANSLATIONS(false), WRITERS(true), BUILDERS(true);

        private final boolean live;

        Section(boolean live) {
            this.live = live;
        }

        /** The relay's wire name. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Whether this section's credits are read from — and so edited on — the live pool. */
        public boolean onLivePool() {
            return live;
        }

        /** The base URL the edit goes to. */
        String baseUrl() {
            return RelayTarget.of(live);
        }
    }

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
    static JsonObject buildPayload(String uuid, Section section, Action action, String from, String to) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid == null ? "" : uuid);
        body.addProperty("section", section.wire());
        body.addProperty("action", action.wire());
        if (action == Action.RENAME) {
            body.addProperty("from", from == null ? "" : from);
            body.addProperty("to", to == null ? "" : to);
        }
        return body;
    }

    /** Rename this player's credit in {@code section} from {@code from} to {@code to}. */
    public static CompletableFuture<Result> rename(Section section, String from, String to) {
        return send(section, Action.RENAME, from, to);
    }

    /** Take this player's name off {@code section} (their line renders as Anonymous, count kept). */
    public static CompletableFuture<Result> remove(Section section) {
        return send(section, Action.REMOVE, "", "");
    }

    /** Put this player's name back on {@code section}. */
    public static CompletableFuture<Result> restore(Section section) {
        return send(section, Action.RESTORE, "", "");
    }

    /**
     * Ask the relay for one edit. Resolves on the calling thread of the HTTP client — hop to the
     * render thread before touching a screen.
     */
    public static CompletableFuture<Result> send(Section section, Action action, String from, String to) {
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null || !RelayChatClient.canConnect()) {
            return CompletableFuture.completedFuture(Result.of(Error.NO_CONSENT));
        }
        try {
            String json = buildPayload(uuid.toString().replace("-", ""), section, action, from, to).toString();
            HttpRequest req = HttpRequest.newBuilder(URI.create(section.baseUrl() + PATH))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> interpret(resp.statusCode(), resp.body()))
                .exceptionally(t -> {
                    LOGGER.debug("[DungeonTrain] Credits: {} {} failed — {}", action, section, t.toString());
                    return Result.of(Error.FAILED);
                });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Credits: {} {} could not start — {}", action, section, t.toString());
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
