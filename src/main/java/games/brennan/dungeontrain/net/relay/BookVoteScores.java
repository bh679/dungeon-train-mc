package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Server-side, read-mostly snapshot of the relay's {@code GET /books/vote-summary} aggregates — the
 * read half of the 👍/👎 book-vote loop. The local weighted book rolls (random + starting registries)
 * consult {@link #effectiveWeight} so community sentiment nudges which books surface, applied AFTER
 * every existing weighting rule; shared-book votes are applied relay-side inside {@code /books/pool}
 * selection instead, so this snapshot only ever influences locally-registered content.
 *
 * <p>{@link games.brennan.dungeontrain.narrative.SharedBookPool}-style threading: a single {@code volatile} immutable map, swapped
 * wholesale by the fire-and-forget {@link #refreshAsync} (own {@link HttpClient}, no-throw,
 * overlapping fetches skipped). {@link #voteFactor} is the relay's bounded formula with a smaller
 * K={@link #SMOOTHING_K} (local pools see far fewer votes per book than the shared pool, so a
 * smaller smoothing constant lets a handful of votes matter): {@code clamp(1 + 0.5·(up−down)/
 * (up+down+K), 0.5, 1.5)}. Relay down / no votes / never fetched → factor exactly 1 and the rolls
 * behave identically to today.</p>
 */
public final class BookVoteScores {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Additive smoothing — smaller than the relay's 10 because local pools see fewer votes/book. */
    static final int SMOOTHING_K = 5;
    static final double FACTOR_MIN = 0.5;
    static final double FACTOR_MAX = 1.5;

    /**
     * Integer headroom for {@link #effectiveWeight}: every candidate's base weight is scaled by
     * {@code 100·factor}, so a neutral book keeps exactly its relative odds and a voted one shifts
     * within ±50% without integer truncation ever zeroing a small base weight.
     */
    private static final int WEIGHT_SCALE = 100;

    private record Tally(int up, int down) {}

    /** {@code "<bookType>:<bookId>"} → tally. Immutable; swapped wholesale by a refresh. */
    private static volatile Map<String, Tally> snapshot = Map.of();

    /** Prevents overlapping in-flight fetches (SharedBookPool pattern). */
    private static volatile boolean fetchInFlight = false;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // relay is HTTP/1.1 (matches SharedBookPool et al.)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private BookVoteScores() {}

    /**
     * Fetch fresh vote aggregates off-thread and swap the snapshot. No-throw; a failed or slow fetch
     * leaves the existing snapshot in place. Skips if a fetch is already in flight.
     */
    public static void refreshAsync() {
        if (fetchInFlight) return;
        fetchInFlight = true;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(DungeonTrain.relayBaseUrl() + "/books/vote-summary"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] vote-summary fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] vote-summary fetch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            applyResponse(resp.body());
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] vote-summary parse failed: {}", t.toString());
                        } finally {
                            fetchInFlight = false;
                        }
                    });
        } catch (Throwable t) {
            fetchInFlight = false;
            LOGGER.debug("[DungeonTrain] vote-summary refresh failed to start: {}", t.toString());
        }
    }

    /**
     * Parse a {@code /books/vote-summary} body and swap the snapshot. No-throw, package-private for
     * unit tests: a malformed body (or a not-ok / shape-mismatched reply) keeps the last good
     * snapshot; individually garbled rows are skipped.
     */
    static void applyResponse(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return;
            JsonObject obj = root.getAsJsonObject();
            if (!obj.has("ok") || !obj.get("ok").getAsBoolean()) return;
            if (!obj.has("votes") || !obj.get("votes").isJsonArray()) return;
            Map<String, Tally> parsed = new HashMap<>();
            for (JsonElement el : obj.getAsJsonArray("votes")) {
                try {
                    if (!el.isJsonObject()) continue;
                    JsonObject v = el.getAsJsonObject();
                    if (!v.has("bookType") || !v.has("bookId")) continue;
                    String key = v.get("bookType").getAsString() + ":" + v.get("bookId").getAsString();
                    int up = v.has("up") ? Math.max(0, v.get("up").getAsInt()) : 0;
                    int down = v.has("down") ? Math.max(0, v.get("down").getAsInt()) : 0;
                    if (up + down > 0) parsed.put(key, new Tally(up, down));
                } catch (RuntimeException ignored) {
                    // one garbled row must not sink the rest
                }
            }
            snapshot = Map.copyOf(parsed);
            LOGGER.debug("[DungeonTrain] vote-summary refreshed: {} voted book(s)", parsed.size());
        } catch (RuntimeException ignored) {
            // malformed body — keep the last good snapshot
        }
    }

    /**
     * The bounded vote multiplier for one book — {@code 1.0} when unvoted, unknown, or the summary
     * has never been fetched (behaviour identical to today).
     */
    public static double voteFactor(String bookType, String bookId) {
        Tally t = snapshot.get(bookType + ":" + bookId);
        if (t == null) return 1.0;
        int total = t.up + t.down;
        if (total <= 0) return 1.0;
        double f = 1.0 + 0.5 * (t.up - t.down) / (double) (total + SMOOTHING_K);
        return f < FACTOR_MIN ? FACTOR_MIN : Math.min(f, FACTOR_MAX);
    }

    /**
     * A base datapack weight with the vote factor applied, pre-scaled by {@code 100} for integer
     * precision — callers must use it for BOTH the total and the walk so the scale cancels. A base of
     * {@code 0} stays {@code 0} (weight-0 books remain excluded); any positive base floors at
     * {@code 1}, so a downvoted book gets rare, never impossible.
     */
    public static int effectiveWeight(String bookType, String bookId, int base) {
        if (base <= 0) return 0;
        return Math.max(1, (int) Math.round(base * (double) WEIGHT_SCALE * voteFactor(bookType, bookId)));
    }

    /** Test/reset hook: drop the snapshot. */
    static void clear() {
        snapshot = Map.of();
        fetchInFlight = false;
    }
}
