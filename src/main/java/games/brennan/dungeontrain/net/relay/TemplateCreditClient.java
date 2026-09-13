package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.BuilderCredit;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Mirrors a template's builder credit to the relay, so the builder leaderboard can count it.
 *
 * <p>The credit itself lives in the kind's {@code weights.json} and ships in the jar — that copy is
 * the one the editor and the Credits page read. The relay's copy exists for one reader: the
 * <b>Most Templates Built</b> board, which has to rank builders across every version of the mod
 * and cannot open a jar to do it.</p>
 *
 * <p>Goes through the <b>operator</b> route and the admin secret this machine holds
 * ({@link RelayTarget#adminSearchBase()}), the same way a live creator search does — a credit is
 * the developer's word on who built something, and a route any client could post to would let
 * anyone plant their own name on the bundled train. A release jar has no admin URL, resolves
 * {@link Outcome#NO_ADMIN} at once and never opens a socket. A credit that could not be mirrored is
 * still saved locally; {@code scripts/relay/sync-template-credits.py} pushes the whole bundled set
 * later, so a missed post is a delay, never a loss.</p>
 *
 * <p>Always addressed to the <b>live</b> pool: that is the one the shipped mod's boards read, and a
 * credit is a fact about the shipped template rather than about whichever relay the editor is
 * pointed at today.</p>
 */
public final class TemplateCreditClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // relay is HTTP/1.1 (bare Node); avoid h2c
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** The pool the shipped mod's boards read — see the class note. */
    static final String CAP = "live";
    static final String PATH = "/templates/credit";

    /** How a mirror attempt ended, for the command reply that says so. */
    public enum Outcome { RECORDED, NO_ADMIN, REJECTED, UNREACHABLE }

    private TemplateCreditClient() {}

    /**
     * Mirror {@code credit} for {@code (kind, id)} — {@code null} clears the relay's row. Never
     * throws and never blocks the caller; the future carries the {@link Outcome}.
     *
     * @param kind the template kind as the relay files it: {@code carriage}, {@code contents}, or a
     *             {@code TrackKind} id such as {@code portal_room}
     * @param id   the template id (its file basename — the same key {@code weights.json} uses)
     */
    public static CompletableFuture<Outcome> record(String kind, String id, BuilderCredit credit) {
        String admin = RelayTarget.adminSearchBase();
        if (admin.isEmpty()) return CompletableFuture.completedFuture(Outcome.NO_ADMIN);
        JsonObject body = new JsonObject();
        body.addProperty("kind", kind);
        body.addProperty("id", id);
        if (credit != null && credit.known()) {
            body.addProperty("uuid", credit.uuid());
            body.addProperty("name", credit.name());
        } else {
            body.add("uuid", null);
        }
        return post(admin + PATH + "?cap=" + CAP, body).thenApply(resp -> {
            if (resp == null) return Outcome.UNREACHABLE;
            if (resp.statusCode() / 100 == 2) return Outcome.RECORDED;
            LOGGER.warn("[DungeonTrain] Relay refused template credit {}:{} — HTTP {} {}",
                kind, id, resp.statusCode(), abbreviate(resp.body()));
            return Outcome.REJECTED;
        });
    }

    private static CompletableFuture<HttpResponse<String>> post(String url, JsonObject body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .exceptionally(e -> {
                        // The URL is a secret; the failure is logged without it.
                        LOGGER.debug("[DungeonTrain] template credit post failed: {}", e.toString());
                        return null;
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] template credit post failed to start: {}", t.toString());
            return CompletableFuture.completedFuture(null);
        }
    }

    private static String abbreviate(String body) {
        if (body == null) return "";
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
