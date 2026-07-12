package games.brennan.dungeontrain.net.relay;
import games.brennan.dungeontrain.DtCore;

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
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Off-thread reader for the relay's {@code GET /<CAP>/books/stats?id=&uuid=} endpoint — the reception
 * stats for one player-written community book. Mirrors {@link games.brennan.dungeontrain.narrative.SharedBookPool}'s
 * fire-and-forget GET pattern (its own {@link HttpClient}, no-throw, best-effort).
 *
 * <p>The relay verifies authorship by UUID and only returns numbers to the book's author; a non-author
 * (or unknown id) gets {@code isAuthor=false} and zeroed stats. Backs the author-only "a familiar book…"
 * line — see {@link games.brennan.dungeontrain.narrative.FamiliarBookGreeter}.</p>
 */
public final class BookStatsClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Pin HTTP/1.1: the relay is a cleartext-capable Node server; Java's default HTTP/2 client can't
    // h2c-upgrade over plaintext http:// (breaks local 127.0.0.1 testing). Harmless in prod — Apache
    // proxies HTTP/1.1 to the origin regardless.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private BookStatsClient() {}

    /**
     * Reception stats for one community book. {@code held} is the count of DISTINCT players who opened
     * it; {@code completers} the distinct players who read it to completion; {@code rereads} the repeat
     * opens; durations in ms; {@code longestPageIndex} is 0-based.
     */
    public record Stats(boolean isAuthor, int held, int completers, int opens,
                        long longestReadMs, long longestPageMs, int longestPageIndex,
                        int pageTurns, int rereads) {}

    /**
     * Fetch stats for {@code bookId} off-thread and hand the parsed result to {@code callback} (invoked
     * on the HTTP completion thread — the caller is responsible for hopping back to the server thread
     * before touching game state). No-throw: a failed / slow / malformed / non-2xx fetch never calls
     * back.
     */
    public static void fetch(int bookId, UUID holder, Consumer<Stats> callback) {
        try {
            String url = DtCore.relayBaseUrl()
                    + "/books/stats?id=" + bookId
                    + "&uuid=" + holder.toString().replace("-", "");
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] book-stats fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] book-stats fetch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            Stats stats = parse(resp.body());
                            if (stats != null) callback.accept(stats);
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] book-stats parse failed: {}", t.toString());
                        }
                    });
        } catch (Throwable t) {
            // Building the request failed synchronously — swallow; the greeter just won't show a line.
            LOGGER.debug("[DungeonTrain] book-stats request failed to start: {}", t.toString());
        }
    }

    /** Parse the relay JSON body into {@link Stats}, or null when it isn't a well-formed ok response. */
    private static Stats parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        if (!o.has("ok") || !o.get("ok").getAsBoolean()) return null;
        boolean isAuthor = o.has("isAuthor") && o.get("isAuthor").getAsBoolean();
        return new Stats(
                isAuthor,
                optInt(o, "held"), optInt(o, "completers"), optInt(o, "opens"),
                optLong(o, "longestReadMs"), optLong(o, "longestPageMs"), optInt(o, "longestPageIndex"),
                optInt(o, "pageTurns"), optInt(o, "rereads"));
    }

    private static int optInt(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static long optLong(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
