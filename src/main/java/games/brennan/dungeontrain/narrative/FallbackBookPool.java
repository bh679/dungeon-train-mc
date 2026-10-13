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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small windows of community books in a FALLBACK language — one per language family — for a writer who
 * has become most of their own language's pool.
 *
 * <p>The relay family-scopes {@code /books/pool} to the host's language and only falls back to English
 * when that family has no books at all. A prolific writer in a small language can therefore end up as
 * most of their own language's pool: once they have read everybody else's, every unread book left is
 * one they wrote. {@link SharedBookSelector} then mixes in books from the player's fallback family —
 * English for a non-English player, the biggest other language ({@link BookLanguages}) for an English
 * one — drawn from the window kept here.</p>
 *
 * <p>Sibling to {@link SharedBookPool}, and deliberately NOT part of it, for the same reasons as
 * {@link AuthorBookPool}: the main snapshot is replaced wholesale when its language changes, and its
 * {@code total} is the denominator of the loot taper — neither may be disturbed by a second language.
 * Each family walks the relay with its OWN session token, so exhausting one window (which makes the
 * relay reset that session's offered set) can never reset another's walk.</p>
 *
 * <p>Fetched lazily: only when a pickup actually hits the mix. Worlds that never do never send it.</p>
 */
public final class FallbackBookPool {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Books per fetch — matches the main window. */
    static final int POOL_LIMIT = 20;

    /** Accumulated window cap per family; oldest evicted first. Matches {@link SharedBookPool}'s. */
    static final int MAX_SNAPSHOT = 200;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // see SharedBookPool — h2c would break local testing
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** family → that family's window (immutable). */
    private static final Map<String, List<SharedBookPool.PoolBook>> SNAPSHOTS = new ConcurrentHashMap<>();
    /** family → its relay session token — see the class javadoc for why each family has its own. */
    private static final Map<String, String> SESSIONS = new ConcurrentHashMap<>();
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private FallbackBookPool() {}

    /** The current window for {@code family} (immutable), possibly empty. */
    public static List<SharedBookPool.PoolBook> snapshot(String family) {
        if (family == null) return List.of();
        return SNAPSHOTS.getOrDefault(family, List.of());
    }

    /**
     * Fetch the next window for {@code family} off-thread and merge it in. No-throw; a fetch for that
     * family already in flight returns immediately.
     *
     * @param family   a language family key ({@link LanguageFamily#of}); the relay accepts it as a locale
     * @param hostUuid the consented host uuid (dashless) or {@code null}/blank — the relay uses it to
     *                 deprioritise books this player has read, exactly as on the main pool
     * @param kidSafe  narrow to kid-safe books, as on the main pool
     */
    public static void refreshAsync(String family, String hostUuid, boolean kidSafe) {
        if (family == null || family.isBlank() || !IN_FLIGHT.add(family)) return;
        try {
            String session = SESSIONS.computeIfAbsent(family, f -> UUID.randomUUID().toString());
            String url = DungeonTrain.relayBaseUrl()
                    + "/books/pool?session=" + session + "&limit=" + POOL_LIMIT + "&pol=1"
                    + SharedBookPool.langParam(family) + SharedBookPool.uuidParam(hostUuid)
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
                                LOGGER.debug("[DungeonTrain] fallback-book fetch ({}) failed: {}", family, err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] fallback-book fetch ({}) -> HTTP {}", family, resp.statusCode());
                                return;
                            }
                            int added = applyResponse(family, resp.body());
                            LOGGER.debug("[DungeonTrain] fallback-book pool ({}) refreshed: {} book(s) ({} new this fetch)",
                                    family, snapshot(family).size(), added);
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] fallback-book parse failed: {}", t.toString());
                        } finally {
                            IN_FLIGHT.remove(family);
                        }
                    });
        } catch (Throwable t) {
            IN_FLIGHT.remove(family);
            LOGGER.debug("[DungeonTrain] fallback-book refresh failed to start: {}", t.toString());
        }
    }

    /**
     * Merge one relay reply into {@code family}'s window by id (existing entries kept, new ones
     * appended, oldest evicted past {@link #MAX_SNAPSHOT}). A malformed or not-ok reply leaves the
     * window untouched.
     *
     * @return how many new ids the reply added
     */
    static int applyResponse(String family, String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return 0;
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("ok") || !obj.get("ok").getAsBoolean()) return 0;
        if (!obj.has("books") || !obj.get("books").isJsonArray()) return 0;
        Map<Integer, SharedBookPool.PoolBook> merged = new LinkedHashMap<>();
        for (SharedBookPool.PoolBook b : snapshot(family)) merged.put(b.id(), b);
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
        SNAPSHOTS.put(family, List.copyOf(out));
        return added;
    }

    /** Test/reset hook. */
    static void clear() {
        SNAPSHOTS.clear();
        IN_FLIGHT.clear();
    }
}
