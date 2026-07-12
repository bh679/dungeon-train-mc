package games.brennan.dungeontrain.narrative;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Server-side, read-mostly cache of approved community books fetched from the Dungeon Train relay's
 * {@code /books/pool} endpoint, for the shared-books DISCOVERY half. Chest loot ({@code
 * ContainerContentsRoller.rollItemStack}) reads a snapshot with {@link #rollShared} to (sometimes)
 * substitute a rolled written book with a community submission crediting its author.
 *
 * <h3>Threading</h3>
 * <p>The snapshot is a single {@code volatile} reference to an immutable list. {@link #rollShared} only
 * reads that reference and never blocks or touches the network — safe to call from the loot roll on the
 * server thread. {@link #refreshAsync} is fire-and-forget off-thread (its own {@link HttpClient}), and
 * atomically swaps in a fresh immutable list when the relay replies. All swaps replace the reference
 * wholesale (never mutate a published list), so a reader always sees a consistent snapshot.</p>
 *
 * <h3>Bounds</h3>
 * <p>Each fetch requests at most {@link #POOL_LIMIT} books and passes the accumulated {@code exclude}
 * set of ids the world has already served, so repeat fetches favour fresh content. The seen-set is
 * capped at {@link #SEEN_CAP} (oldest ids evicted) so it can't grow without bound.</p>
 */
public final class SharedBookPool {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Max books requested per fetch. */
    static final int POOL_LIMIT = 20;

    /** Upper bound on the accumulated served/seen id set passed as {@code exclude}. */
    static final int SEEN_CAP = 200;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // relay is HTTP/1.1; avoids h2c against a bare-Node relay (matches NarrativePool/DeathReporter/BookStatsClient)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** One approved community book, materialised from the relay's pool response. */
    public record PoolBook(int id, String title, String author, List<String> pages) {}

    /** Current immutable snapshot. Replaced wholesale by {@link #refreshAsync}; read by {@link #rollShared}. */
    private static volatile List<PoolBook> snapshot = List.of();

    /**
     * Total count of APPROVED community books in the relay pool (the whole loot-eligible universe, NOT
     * the ≤{@link #POOL_LIMIT} window in {@link #snapshot}). Reported by the relay's {@code /books/pool}
     * response {@code total} field and refreshed on every fetch. Feeds the shared-book loot taper (the
     * denominator of "community books read / total"). Stays 0 against an older relay that omits the
     * field, which the taper reads as "unknown" → no taper (today's flat-max behaviour).
     */
    private static volatile int approvedTotal = 0;

    /**
     * Ids the world has served / seen, used as the {@code exclude} filter so repeat fetches favour
     * fresh books. Insertion-ordered so the oldest ids evict first at {@link #SEEN_CAP}. Guarded by its
     * own monitor (only touched from the async fetch continuation + its exclude read).
     */
    private static final Set<Integer> SEEN_IDS = new LinkedHashSet<>();

    /** Prevents overlapping in-flight fetches (a slow relay shouldn't stack requests every tick). */
    private static volatile boolean fetchInFlight = false;

    private SharedBookPool() {}

    /**
     * Pick a community book at random from the current snapshot and build a plain written book
     * crediting its author. Returns {@link ItemStack#EMPTY} when the snapshot is empty. Deterministic
     * per {@code rollSeed} so the same chest at the same world seed always yields the same book.
     *
     * <p>The built stack carries NO {@link SharedBookTag} — a found book is not part of the CONTRIBUTION
     * half's immediate-burn flow, and reading it never counts as a story read. It IS stamped with two
     * markers on distinct CUSTOM_DATA keys: {@link SharedBookFoundTag} (the read-event handler uses it to
     * grant the "read a stranger's book" advancement, and — together with its held-marker — the burn
     * lifecycle burns it after being read/dropped, same as a random loot book, since it was written by a
     * real player) and a {@link SharedBookReadTag} pool id (so a read can be attributed to the specific
     * submission on the data-explorer's Books page, inert to loot/burning/progression).</p>
     */
    public static ItemStack rollShared(long rollSeed) {
        List<PoolBook> pool = snapshot; // single volatile read → consistent snapshot
        if (pool.isEmpty()) return ItemStack.EMPTY;
        int index = (int) (Long.remainderUnsigned(mix(rollSeed), pool.size()));
        PoolBook book = pool.get(index);
        ItemStack stack = BookFactory.buildPlainBook(book.title(), book.author(), book.pages());
        SharedBookFoundTag.stamp(stack);               // "read a stranger's book" advancement marker
        SharedBookReadTag.stampId(stack, book.id());   // read-telemetry identity only
        return stack;
    }

    /** Whether the pool currently holds any books (cheap volatile read). */
    public static boolean isEmpty() {
        return snapshot.isEmpty();
    }

    /**
     * Total approved community books in the relay pool as of the last successful fetch, or 0 when the
     * relay is unreachable / has never replied / is too old to report it. The shared-book loot taper
     * treats 0 as "unknown" and skips the taper (flat config max), so this is safe to read from the
     * loot roll without a null/loading guard.
     */
    public static int approvedTotal() {
        return approvedTotal;
    }

    /**
     * Fetch a fresh pool from the relay off-thread and swap in the new snapshot. No-throw; a failed or
     * slow fetch leaves the existing snapshot in place. Skips if a fetch is already in flight.
     *
     * @param hostLang the host player's raw client locale (e.g. {@code "en_us"}) for language-matched
     *                 delivery, or {@code ""}/{@code null} to leave the pool unfiltered. See
     *                 {@link WorldLanguage#hostLocale}.
     */
    public static void refreshAsync(String hostLang) {
        if (fetchInFlight) return;
        fetchInFlight = true;
        try {
            String exclude = excludeCsv();
            boolean hadExclude = !exclude.isEmpty();
            String url = DungeonTrain.relayBaseUrl()
                    + "/books/pool?exclude=" + exclude + "&limit=" + POOL_LIMIT + langParam(hostLang);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] shared-book pool fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] shared-book pool fetch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            applyResponse(resp.body(), hadExclude);
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] shared-book pool parse failed: {}", t.toString());
                        } finally {
                            fetchInFlight = false;
                        }
                    });
        } catch (Throwable t) {
            // Building the request failed synchronously — release the guard so the next tick can retry.
            fetchInFlight = false;
            LOGGER.debug("[DungeonTrain] shared-book pool refresh failed to start: {}", t.toString());
        }
    }

    /**
     * Parse the relay JSON body, build the new snapshot, and accumulate served ids into the seen-set.
     *
     * <p>{@code hadExclude} is whether this fetch sent a non-empty {@code exclude} filter — it
     * disambiguates the two ways a fetch can come back with zero books:</p>
     * <ul>
     *   <li><b>Exclude-starvation</b> ({@code hadExclude} true, 0 books): the seen-set has grown to
     *       cover every book the relay holds, so the filter excluded them all. This is NOT an empty
     *       pool — reset the seen-set so the next refresh re-pulls the whole pool (a "silent full
     *       cycle", mirroring the random-book picker), and KEEP the current snapshot so loot keeps
     *       serving books meanwhile. Without this the pool empties permanently the moment every book
     *       has been served once.</li>
     *   <li><b>Genuinely empty pool</b> ({@code hadExclude} false, 0 books): nothing was excluded and
     *       still nothing came back — the relay really has no approved books. Clear the snapshot.</li>
     * </ul>
     */
    static void applyResponse(String body, boolean hadExclude) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return;
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("ok") || !obj.get("ok").getAsBoolean()) {
            LOGGER.debug("[DungeonTrain] shared-book pool response not ok");
            return;
        }
        // Capture the relay-reported total of APPROVED books before the books-array checks, so it is
        // refreshed even on the exclude-starvation / empty-books branches (the relay counts the whole
        // pool, not the excluded window). A relay too old to send `total` leaves the last value intact.
        if (obj.has("total") && obj.get("total").isJsonPrimitive()) {
            try {
                approvedTotal = Math.max(0, obj.get("total").getAsInt());
            } catch (RuntimeException ignored) {
                // non-numeric total — keep the last known value
            }
        }
        if (!obj.has("books") || !obj.get("books").isJsonArray()) {
            // Malformed reply — keep the last good snapshot rather than wiping loot over a transient blip.
            LOGGER.debug("[DungeonTrain] shared-book pool response missing books array — keeping last snapshot");
            return;
        }
        List<PoolBook> parsed = new ArrayList<>();
        for (JsonElement el : obj.getAsJsonArray("books")) {
            if (!el.isJsonObject()) continue;
            PoolBook book = parseBook(el.getAsJsonObject());
            if (book != null) parsed.add(book);
        }
        if (parsed.isEmpty()) {
            if (hadExclude) {
                // Exclude-starvation, not an empty pool — reset the seen-set and keep serving the
                // current snapshot; the next refresh (empty exclude) re-pulls the full pool.
                resetSeen();
                LOGGER.debug("[DungeonTrain] shared-book pool exhausted by exclude filter — reset seen-set, keeping {} book(s)",
                        snapshot.size());
            } else {
                snapshot = List.of();
                LOGGER.debug("[DungeonTrain] shared-book pool is empty");
            }
            return;
        }
        // Publish an immutable snapshot (copy so no external ref can mutate it).
        snapshot = List.copyOf(parsed);
        rememberSeen(parsed);
        LOGGER.debug("[DungeonTrain] shared-book pool refreshed: {} book(s)", parsed.size());
    }

    /** Materialise one pool entry; returns {@code null} if it lacks the required fields. */
    private static PoolBook parseBook(JsonObject o) {
        if (!o.has("id")) return null;
        int id = o.get("id").getAsInt();
        String title = o.has("title") && !o.get("title").isJsonNull() ? o.get("title").getAsString() : "";
        String author = o.has("author") && !o.get("author").isJsonNull() ? o.get("author").getAsString() : "";
        List<String> pages = new ArrayList<>();
        if (o.has("pages") && o.get("pages").isJsonArray()) {
            JsonArray arr = o.getAsJsonArray("pages");
            for (JsonElement p : arr) {
                pages.add(p.isJsonNull() ? "" : p.getAsString());
            }
        }
        return new PoolBook(id, title, author, pages);
    }

    private static synchronized void rememberSeen(List<PoolBook> books) {
        for (PoolBook b : books) {
            SEEN_IDS.add(b.id());
        }
        // Evict oldest ids beyond the cap (LinkedHashSet iterates in insertion order).
        while (SEEN_IDS.size() > SEEN_CAP) {
            var it = SEEN_IDS.iterator();
            it.next();
            it.remove();
        }
    }

    private static synchronized String excludeCsv() {
        if (SEEN_IDS.isEmpty()) return "";
        return SEEN_IDS.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** The {@code &lang=<locale>} query fragment for language-matched delivery, or {@code ""} when blank. */
    static String langParam(String hostLang) {
        if (hostLang == null || hostLang.isBlank()) return "";
        return "&lang=" + URLEncoder.encode(hostLang, StandardCharsets.UTF_8);
    }

    /**
     * Clear the served/seen id set so the next refresh sends an empty {@code exclude} and re-pulls the
     * whole relay pool. Called when the exclude filter has starved the pool (see {@link #applyResponse}),
     * mirroring the random-book picker's silent full-cycle reset.
     */
    private static synchronized void resetSeen() {
        SEEN_IDS.clear();
    }

    /** Splittable-mix so a raw roll seed spreads uniformly across the pool index. */
    private static long mix(long seed) {
        long state = seed ^ 0x53484152454442L; // "SHAREDB"
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        state = state ^ (state >>> 31);
        return state;
    }

    /** Test/reset hook: drop the snapshot and seen-set (used by unit tests and on server stop). */
    static synchronized void clear() {
        snapshot = List.of();
        SEEN_IDS.clear();
        fetchInFlight = false;
        approvedTotal = 0;
    }
}
