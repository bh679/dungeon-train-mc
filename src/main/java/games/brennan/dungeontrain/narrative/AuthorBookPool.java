package games.brennan.dungeontrain.narrative;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One catalogue per author — the books an author-locked portal room draws from.
 *
 * <p>Sibling to {@link SharedBookPool}, and deliberately NOT part of it. That pool is the world's
 * single mixed snapshot, and its {@code total} is the denominator of the shared-book loot taper; an
 * author's dozen books must never be merged into it or counted as the corpus. Everything here is
 * keyed by the relay's opaque author token, which the mod treats as a handle and never interprets.</p>
 *
 * <h3>Threading</h3>
 * <p>Same contract as {@link SharedBookPool}: a {@link ConcurrentHashMap} of immutable lists, read
 * from the server thread without blocking, written wholesale by the off-thread fetch. A reader always
 * sees a complete catalogue or none.</p>
 *
 * <h3>Why the whole catalogue, once</h3>
 * <p>The relay skips its curation weight tiers for an author-scoped query, so one fetch of
 * {@link #POOL_LIMIT} covers most authors outright. There is no tier walk to advance and no window to
 * exhaust — the repeat loop that shaped {@link SharedBookPool}'s refresh logic cannot happen here.
 * A catalogue is therefore fetched once and reused until the room retires or the game restarts.</p>
 */
public final class AuthorBookPool {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Max books requested per author.
     *
     * <p>Far above the ordinary pool's twenty-book loot window, and deliberately: a library room
     * deals this catalogue across its shelves and leaves the rest empty, so a short fetch would make
     * every prolific author look like they had written twenty books. The relay raises its own ceiling
     * for author-scoped queries to match (see {@code MAX_AUTHOR_POOL_LIMIT}).</p>
     */
    static final int POOL_LIMIT = 200;

    /**
     * How many authors' catalogues are kept at once. A player meets one room at a time and rooms
     * retire behind them, so this only has to cover the handful in flight; past it the
     * least-recently-fetched is dropped and would be re-fetched if that room came back.
     */
    static final int MAX_CATALOGUES = 8;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // see BookAuthorsClient — h2c would break local testing
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * token → that author's books. Access-ordered and bounded, so the map is an LRU of the rooms
     * currently in play. Synchronised on itself rather than concurrent: every access is either a
     * pickup or a fetch completion, both rare, and access-order eviction needs the read to mutate.
     */
    private static final Map<String, List<SharedBookPool.PoolBook>> CATALOGUES =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<SharedBookPool.PoolBook>> eldest) {
                    return size() > MAX_CATALOGUES;
                }
            };

    /** Tokens with a fetch in flight, so a room being walked through does not stack requests. */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private AuthorBookPool() {}

    /** This author's books, or an empty list when none have been fetched (or the author has none). */
    public static List<SharedBookPool.PoolBook> booksFor(String token) {
        if (token == null || token.isBlank()) return List.of();
        synchronized (CATALOGUES) {
            List<SharedBookPool.PoolBook> hit = CATALOGUES.get(token);
            return hit == null ? List.of() : hit;
        }
    }

    /** True once a fetch for {@code token} has completed, whatever it found — including nothing. */
    public static boolean isFetched(String token) {
        if (token == null || token.isBlank()) return false;
        synchronized (CATALOGUES) {
            return CATALOGUES.containsKey(token);
        }
    }

    /** True while a fetch for {@code token} is on its way. */
    public static boolean isFetching(String token) {
        return token != null && IN_FLIGHT.contains(token);
    }

    /**
     * Fetch one author's catalogue off-thread. No-throw, and idempotent: a token already fetched or
     * already in flight returns immediately.
     *
     * <p>An author who turns out to have nothing servable is cached as an EMPTY list rather than left
     * absent. That is what tells {@link games.brennan.dungeontrain.portal.PortalRoomAuthorLocks} to
     * rotate to somebody else instead of waiting forever on a catalogue that is never coming.</p>
     *
     * @param hostLang the host's raw client locale, for language-matched delivery. The relay falls
     *                 back to this author's OWN language rather than to English when they wrote in
     *                 neither — a locked room is that person's library whatever they wrote in.
     * @param kidSafe  narrow to kid-safe books, on the same reasoning as {@link SharedBookPool}
     */
    public static void refreshAsync(String token, String hostLang, boolean kidSafe) {
        if (token == null || token.isBlank()) return;
        if (isFetched(token) || !IN_FLIGHT.add(token)) return;
        try {
            String url = DungeonTrain.relayBaseUrl()
                    + "/books/pool?limit=" + POOL_LIMIT
                    + "&pol=1"
                    + "&author=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                    + langParam(hostLang)
                    + (kidSafe ? "&kidsafe=1" : "");
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] author-book fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] author-book fetch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            List<SharedBookPool.PoolBook> books = parse(resp.body());
                            put(token, books);
                            LOGGER.info("[DungeonTrain] author catalogue fetched: {} book(s)", books.size());
                        } catch (Throwable t) {
                            // A malformed reply is NOT cached: an author whose catalogue never parsed
                            // should be retried, unlike one the relay says has nothing.
                            LOGGER.debug("[DungeonTrain] author-book parse failed: {}", t.toString());
                        } finally {
                            IN_FLIGHT.remove(token);
                        }
                    });
        } catch (Throwable t) {
            IN_FLIGHT.remove(token);
            LOGGER.debug("[DungeonTrain] author-book refresh failed to start: {}", t.toString());
        }
    }

    /**
     * Parse the relay's pool reply into this author's books.
     *
     * <p>Reuses {@link SharedBookPool}'s per-book parse so an author's book is the same
     * {@link SharedBookPool.PoolBook} an ordinary one is — the selector, the stack builder and every
     * downstream tag then work on it unchanged.</p>
     */
    static List<SharedBookPool.PoolBook> parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return List.of();
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("ok") || !obj.get("ok").getAsBoolean()) return List.of();
        if (!obj.has("books") || !obj.get("books").isJsonArray()) return List.of();
        List<SharedBookPool.PoolBook> out = new ArrayList<>();
        for (JsonElement el : obj.getAsJsonArray("books")) {
            if (!el.isJsonObject()) continue;
            SharedBookPool.PoolBook book = SharedBookPool.parseBook(el.getAsJsonObject());
            if (book != null) out.add(book);
        }
        return List.copyOf(out);
    }

    /** Publish a catalogue (immutable, wholesale) under {@code token}. */
    static void put(String token, List<SharedBookPool.PoolBook> books) {
        synchronized (CATALOGUES) {
            CATALOGUES.put(token, List.copyOf(books));
        }
    }

    /** The {@code &lang=<locale>} fragment, or {@code ""} when blank — mirrors {@link SharedBookPool}. */
    private static String langParam(String hostLang) {
        if (hostLang == null || hostLang.isBlank()) return "";
        return "&lang=" + URLEncoder.encode(hostLang, StandardCharsets.UTF_8);
    }

    /** Test/reset hook: drop every catalogue (used by unit tests and on server stop). */
    public static void clear() {
        synchronized (CATALOGUES) {
            CATALOGUES.clear();
        }
        IN_FLIGHT.clear();
    }
}
