package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.BookSafeText;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Off-thread reader for the relay's {@code GET /<CAP>/books/authors} endpoint — who has written
 * enough community books to fill a room. Mirrors {@link BookStatsClient}'s fire-and-forget GET
 * pattern (its own {@link HttpClient}, no-throw, best-effort).
 *
 * <p>Backs the author-locked portal rooms: a room picks one {@link Author} here, then passes its
 * {@link Author#token()} to {@code /books/pool?author=} to be served only that person's catalogue.
 * See {@link games.brennan.dungeontrain.portal.PortalRoomAuthorLocks}.</p>
 *
 * <h3>Why a token rather than a uuid</h3>
 * <p>The relay has never shipped author uuids to the game — its public book shape deliberately omits
 * them — and answering "who is prolific?" must not be the thing that changes that. The token is a
 * salted hash the relay resolves back on the pool query; the mod treats it as opaque and stores it
 * nowhere durable.</p>
 */
public final class BookAuthorsClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Pin HTTP/1.1 for the same reason BookStatsClient does: Java's default HTTP/2 client cannot
    // h2c-upgrade over plaintext http://, which breaks local 127.0.0.1 testing against a bare-Node
    // relay. Harmless in prod.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** How many candidates a directory fetch asks for. Enough that consecutive rooms rarely repeat. */
    private static final int DIRECTORY_LIMIT = 25;

    private BookAuthorsClient() {}

    /**
     * One author the relay is willing to name: an opaque {@code token} to fetch their books with, the
     * display {@code name} (a signature — public on every book already), and how many approved books
     * stand behind it.
     */
    public record Author(String token, String name, int count, boolean mine) {

        /**
         * An author page fetched as somebody else's — the ordinary {@code player} / {@code signature}
         * directories. Never {@code mine}, whoever it turns out to name.
         */
        public static Author other(String token, String name, int count) {
            return new Author(token, name, count, false);
        }
    }

    /**
     * Fetch the authors within this room's book range and hand the list to
     * {@code callback} (invoked on the HTTP completion thread — the caller must hop back to the server
     * thread before touching game state). No-throw: a failed / slow / malformed / non-2xx fetch calls
     * back with an empty list rather than not at all, so a caller waiting on it is never left hanging.
     *
     * @param kind     {@code "player"}, {@code "signature"} or {@code "self"} — see
     *                 {@link games.brennan.dungeontrain.portal.PortalRoomBooks.Share#directoryKind()}
     * @param maxBooks upper bound on an author's approved count, or
     *                 {@link games.brennan.dungeontrain.portal.PortalRoomBooks#NO_MAXIMUM} for none —
     *                 a room built to feel like a modest private collection wants a modest author
     * @param uuid     required by {@code kind=self}, ignored otherwise; may be {@code null}
     * @param kidSafe  narrow to books the relay judged fit for a young child, so a Kid-mode world can
     *                 never be handed an author whose whole catalogue it is not allowed to see
     */
    public static void fetch(String kind, int minBooks, int maxBooks, UUID uuid, boolean kidSafe,
                             Consumer<List<Author>> callback) {
        // `self` is the only kind that names the caller, so it is the only one whose entries may be
        // marked `mine` — see parse(String, boolean).
        final boolean self = "self".equals(kind);
        try {
            StringBuilder url = new StringBuilder(DungeonTrain.relayBaseUrl())
                    .append("/books/authors?kind=")
                    .append(URLEncoder.encode(kind, StandardCharsets.UTF_8))
                    .append("&min=").append(Math.max(0, minBooks))
                    .append("&limit=").append(DIRECTORY_LIMIT);
            if (maxBooks > 0) url.append("&max=").append(maxBooks);
            if (uuid != null) {
                url.append("&uuid=").append(uuid.toString().replace("-", ""));
            }
            if (kidSafe) url.append("&kidsafe=1");
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        List<Author> out = List.of();
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] book-authors fetch failed: {}", err.toString());
                            } else if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] book-authors fetch -> HTTP {}", resp.statusCode());
                            } else {
                                out = parse(resp.body(), self);
                            }
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] book-authors parse failed: {}", t.toString());
                        } finally {
                            try {
                                callback.accept(out);
                            } catch (Throwable t) {
                                LOGGER.debug("[DungeonTrain] book-authors callback failed: {}", t.toString());
                            }
                        }
                    });
        } catch (Throwable t) {
            // Building the request failed synchronously — still call back, so the caller's in-flight
            // guard is released and the next attempt can run.
            LOGGER.debug("[DungeonTrain] book-authors fetch failed to start: {}", t.toString());
            try {
                callback.accept(List.of());
            } catch (Throwable ignored) {
                // nothing left to do
            }
        }
    }

    /**
     * Parse {@code {ok, authors:[{token,name,count}]}}; anything malformed yields an empty list.
     *
     * <p>{@code mine} is stamped here, from the KIND THAT WAS ASKED FOR, and nowhere else. It has to
     * be decided at the source: a {@code player}-kind directory contains every author including the
     * reader, so the same {@code p…} token can arrive both as "my own shelf" and as "a stranger's
     * shelf I happened to draw", and only the request that produced it can tell those apart. Anything
     * that tried to work it out downstream — comparing tokens against a cached self page, say — would
     * answer "no" while that page was still in flight and flip afterwards, which is exactly the window
     * in which a catalogue gets cached under the wrong key.</p>
     */
    static List<Author> parse(String body, boolean mine) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return List.of();
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("ok") || !obj.get("ok").getAsBoolean()) return List.of();
        if (!obj.has("authors") || !obj.get("authors").isJsonArray()) return List.of();
        List<Author> out = new ArrayList<>();
        for (JsonElement el : obj.getAsJsonArray("authors")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("token") || o.get("token").isJsonNull()) continue;
            String token = o.get("token").getAsString();
            if (token.isBlank()) continue;
            // Sanitized at the parse boundary: this name reaches chat lines and lectern tribute
            // pages that do NOT pass through BookFactory, so it has no other guard.
            String name = BookSafeText.sanitizeName(
                o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : "");
            int count = 0;
            if (o.has("count") && o.get("count").isJsonPrimitive()) {
                try {
                    count = Math.max(0, o.get("count").getAsInt());
                } catch (RuntimeException ignored) {
                    // non-numeric count — a nameable author with an unknown tally is still usable
                }
            }
            out.add(new Author(token, name, count, mine));
        }
        return List.copyOf(out);
    }
}
