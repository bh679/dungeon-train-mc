package games.brennan.dungeontrain.narrative;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A small window of ENGLISH community books, kept for players on a non-English world whose own
 * language has run dry of anyone else's writing.
 *
 * <p>The relay family-scopes {@code /books/pool} to the host's language and only falls back to English
 * when that family has no books at all. A prolific writer in a small language can therefore end up as
 * most of their own language's pool: once they have read everybody else's, every unread book left is
 * one they wrote, and the train hands them nothing but their own work. {@link SharedBookSelector} mixes
 * books from this window in at that point.</p>
 *
 * <p>Sibling to {@link SharedBookPool}, and deliberately NOT part of it, for the same reasons as
 * {@link AuthorBookPool}: the main snapshot is replaced wholesale when its language changes, and its
 * {@code total} is the denominator of the loot taper — neither may be disturbed by a second language.
 * It also walks the relay with its OWN session token, so exhausting this window (which makes the relay
 * reset that session's offered set) can never reset the main window's walk.</p>
 *
 * <p>Fetched lazily: only when a pickup actually hits the fallback. Worlds that never do — every
 * English one, and most others — never send this request.</p>
 */
public final class EnglishFallbackPool {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The locale requested. Any {@code en_*} lands on the relay's default (English) family. */
    static final String LANG = "en_us";

    /** Books per fetch — matches the main window. */
    static final int POOL_LIMIT = 20;

    /** Accumulated window cap; oldest evicted first. Matches {@link SharedBookPool}'s. */
    static final int MAX_SNAPSHOT = 200;

    /** This window's own relay session — see the class javadoc for why it is not the main pool's. */
    private static final String SESSION = UUID.randomUUID().toString();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // see SharedBookPool — h2c would break local testing
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static volatile List<SharedBookPool.PoolBook> snapshot = List.of();
    private static volatile boolean fetchInFlight = false;

    private EnglishFallbackPool() {}

    /** The current English window (immutable), possibly empty. */
    public static List<SharedBookPool.PoolBook> snapshot() {
        return snapshot;
    }

    public static boolean isRefreshInFlight() {
        return fetchInFlight;
    }

    /**
     * Fetch the next English window off-thread and merge it in. No-throw; a fetch already in flight
     * returns immediately.
     *
     * @param hostUuid the consented host uuid (dashless) or {@code null}/blank — the relay uses it to
     *                 deprioritise books this player has read, exactly as on the main pool
     * @param kidSafe  narrow to kid-safe books, as on the main pool
     */
    public static void refreshAsync(String hostUuid, boolean kidSafe) {
        if (fetchInFlight) return;
        fetchInFlight = true;
        try {
            String url = DungeonTrain.relayBaseUrl()
                    + "/books/pool?session=" + SESSION + "&limit=" + POOL_LIMIT + "&pol=1"
                    + SharedBookPool.langParam(LANG) + SharedBookPool.uuidParam(hostUuid)
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
                                LOGGER.debug("[DungeonTrain] english-fallback fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] english-fallback fetch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            int added = applyResponse(resp.body());
                            LOGGER.debug("[DungeonTrain] english-fallback pool refreshed: {} book(s) ({} new this fetch)",
                                    snapshot.size(), added);
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] english-fallback parse failed: {}", t.toString());
                        } finally {
                            fetchInFlight = false;
                        }
                    });
        } catch (Throwable t) {
            fetchInFlight = false;
            LOGGER.debug("[DungeonTrain] english-fallback refresh failed to start: {}", t.toString());
        }
    }

    /**
     * Merge one relay reply into the window by id (existing entries kept, new ones appended, oldest
     * evicted past {@link #MAX_SNAPSHOT}). A malformed or not-ok reply leaves the window untouched.
     *
     * @return how many new ids the reply added
     */
    static int applyResponse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return 0;
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("ok") || !obj.get("ok").getAsBoolean()) return 0;
        if (!obj.has("books") || !obj.get("books").isJsonArray()) return 0;
        Map<Integer, SharedBookPool.PoolBook> merged = new LinkedHashMap<>();
        for (SharedBookPool.PoolBook b : snapshot) merged.put(b.id(), b);
        int added = 0;
        for (JsonElement el : obj.getAsJsonArray("books")) {
            if (!el.isJsonObject()) continue;
            SharedBookPool.PoolBook book = SharedBookPool.parseBook(el.getAsJsonObject());
            if (book == null || merged.containsKey(book.id())) continue;
            merged.put(book.id(), book);
            added++;
        }
        List<SharedBookPool.PoolBook> out = new ArrayList<>(merged.values());
        if (out.size() > MAX_SNAPSHOT) out = out.subList(out.size() - MAX_SNAPSHOT, out.size());
        snapshot = List.copyOf(out);
        return added;
    }

    /** Test/reset hook. */
    static void clear() {
        snapshot = List.of();
        fetchInFlight = false;
    }
}
