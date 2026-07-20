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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

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
 * <h3>De-duplication (server-side)</h3>
 * <p>Each fetch requests at most {@link #POOL_LIMIT} books and identifies this world with a stable
 * per-process {@link #SESSION} token ({@code &session=}). The relay tracks which ids it has already
 * offered this session and hands back fresh content on repeat fetches, recycling once the whole pool has
 * been served — so the client no longer accumulates a "seen" set or ships a growing {@code exclude=}
 * CSV (a T2 scaling fix). Against an older relay that ignores {@code session} the fetch still returns a
 * random window — graceful degradation, just without the dedup.</p>
 */
public final class SharedBookPool {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Max books requested per fetch. */
    static final int POOL_LIMIT = 20;

    /**
     * Stable per-process token identifying THIS world/server to the relay's server-side pool session
     * state. Generated once at class load — the same lifetime the old process-static seen-set had, so a
     * game restart starts a fresh session (the relay re-offers), exactly as before. Opaque to the relay.
     */
    private static final String SESSION = UUID.randomUUID().toString();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // relay is HTTP/1.1; avoids h2c against a bare-Node relay (matches NarrativePool/DeathReporter/BookStatsClient)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * One approved community book, materialised from the relay's pool response.
     *
     * <p>{@code lang} is the AUTHOR's raw client locale as stored by the relay (e.g. {@code "en_us"}), or
     * {@code null} for legacy untagged books. It is player-independent, so it is parsed once here; the
     * per-player language relationship (is this MY language?) is derived at selection time by
     * {@link SharedBookSelector}, because one shared snapshot serves every player.</p>
     *
     * <p>{@code weight} is the moderator-assigned curation weight. The relay ALREADY restricts a pool
     * response to its single highest weight tier, so within one snapshot this is usually uniform; it is
     * carried anyway so the selector's translated-penalty comparison has a base value. Absent from the
     * relay payload today → defaults to 1 (see {@link #parseBook}).</p>
     */
    public record PoolBook(int id, String title, String author, List<String> pages, String lang, int weight) {}

    /**
     * Upper bound on the accumulated snapshot. Fetches MERGE into it (see {@link #applyResponse}) so a
     * long session would otherwise grow it without limit; past this the oldest entries are evicted.
     */
    static final int MAX_SNAPSHOT = 200;

    /**
     * Current immutable snapshot. Fetches merge into it rather than replacing it (see
     * {@link #applyResponse}); read by {@link #rollShared} and {@link SharedBookSelector}.
     */
    private static volatile List<PoolBook> snapshot = List.of();

    /**
     * The locale {@link #snapshot} was accumulated for. When a fetch comes back for a DIFFERENT locale the
     * accumulated books are wrong-language and are replaced wholesale rather than merged.
     */
    private static volatile String snapshotLang = null;

    /**
     * Total count of APPROVED community books in the relay pool (the whole loot-eligible universe, NOT
     * the ≤{@link #POOL_LIMIT} window in {@link #snapshot}). Reported by the relay's {@code /books/pool}
     * response {@code total} field and refreshed on every fetch. Feeds the shared-book loot taper (the
     * denominator of "community books read / total"). Stays 0 against an older relay that omits the
     * field, which the taper reads as "unknown" → no taper (today's flat-max behaviour).
     */
    private static volatile int approvedTotal = 0;

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
        return buildStack(pool.get(index));
    }

    /**
     * The current immutable pool snapshot (a single volatile read → consistent view). Used by
     * {@link SharedBookSelector} to run the per-player priority chain over the full window rather than the
     * uniform pick {@link #rollShared} makes. Never mutate the returned list.
     */
    public static List<PoolBook> snapshot() {
        return snapshot;
    }

    /**
     * Build a plain written book for a specific pool entry and stamp the same discovery/telemetry markers
     * {@link #rollShared} applies. Used by {@link SharedBookSelector} once it has picked a book per-player,
     * so both the uniform and the curated paths produce identically-tagged stacks.
     */
    public static ItemStack buildStack(PoolBook book) {
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
     * Whether a pool fetch is currently in flight — i.e. a NEW window (the relay's next weight tier) is
     * on its way. Callers that have exhausted the current window use this to wait for it rather than
     * re-serving a book the player just had. Clears on failure too, so a dead relay can't wedge a caller
     * into waiting forever.
     */
    public static boolean isRefreshInFlight() {
        return fetchInFlight;
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
            String url = DungeonTrain.relayBaseUrl()
                    + "/books/pool?session=" + SESSION + "&limit=" + POOL_LIMIT + langParam(hostLang);
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
                            applyResponse(resp.body(), hostLang);
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
     * Parse the relay JSON body and merge it into the snapshot.
     *
     * <h3>Why this ACCUMULATES rather than replaces</h3>
     * <p>The relay answers with ONE weight tier at a time and marks those ids "offered" for the session
     * as it hands them over — it will not offer them again until the session recycles. The refresh timer,
     * however, fires on a fixed ~30s cadence regardless of whether the game consumed that window. Replacing
     * the snapshot wholesale therefore STRANDED books: a curated top-weight book that arrived but wasn't
     * picked up before the next tick vanished from the snapshot and, because the relay considered it
     * already offered, did not come back until the whole session recycled — by which point selection had
     * walked down to the lowest tier. Observed in play as a weight-4 book arriving after the weight-0
     * floor had started.
     *
     * <p>Merging instead means a book stays a candidate until it is actually served. It also makes the
     * selector's own weight tier meaningful: with several tiers resident at once,
     * {@link SharedBookSelector} enforces "highest weight first" PER PLAYER rather than depending on the
     * timing of relay windows. Bounded by {@link #MAX_SNAPSHOT} (oldest evicted first) so a long session
     * can't grow it without limit.</p>
     *
     * <p>Read books are deliberately NOT pruned here: "read" is per-player but this snapshot is shared by
     * everyone on the server, so dropping a book because one player read it would deny it to the others.
     * Per-player read preference is applied at selection time instead (unread-first).</p>
     *
     * <p>A language change DOES replace wholesale — accumulated books are in the wrong language and must
     * not linger. A malformed reply keeps the last good snapshot rather than wiping loot over a blip.</p>
     */
    static void applyResponse(String body) {
        applyResponse(body, snapshotLang);
    }

    /** @param fetchLang the locale this response was fetched for; a change from {@link #snapshotLang} replaces. */
    static void applyResponse(String body, String fetchLang) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return;
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("ok") || !obj.get("ok").getAsBoolean()) {
            LOGGER.debug("[DungeonTrain] shared-book pool response not ok");
            return;
        }
        // Capture the relay-reported total of APPROVED books before the books-array checks, so it is
        // refreshed even on the empty-books branch (the relay counts the whole pool, not the window). A
        // relay too old to send `total` leaves the last value intact.
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
        boolean langChanged = !java.util.Objects.equals(fetchLang, snapshotLang);
        if (parsed.isEmpty()) {
            // Nothing to add. Only wipe when the LANGUAGE changed — then the accumulated books are all
            // wrong-language and must go. Otherwise keep what we have: an empty window mid-session is not
            // evidence that the previously-served books stopped existing.
            if (langChanged) {
                snapshot = List.of();
                snapshotLang = fetchLang;
                LOGGER.debug("[DungeonTrain] shared-book pool cleared (language changed, no books for it)");
            } else {
                LOGGER.debug("[DungeonTrain] shared-book pool: empty window, keeping {} accumulated", snapshot.size());
            }
            return;
        }

        List<PoolBook> merged;
        if (langChanged) {
            merged = List.copyOf(parsed);
        } else {
            // Keep existing entries (and their order) and append genuinely new ids.
            LinkedHashMap<Integer, PoolBook> byId = new LinkedHashMap<>();
            for (PoolBook b : snapshot) byId.put(b.id(), b);
            for (PoolBook b : parsed) byId.putIfAbsent(b.id(), b);
            List<PoolBook> all = new ArrayList<>(byId.values());
            // Bound: drop the OLDEST accumulated entries, keeping the freshest window intact.
            if (all.size() > MAX_SNAPSHOT) all = all.subList(all.size() - MAX_SNAPSHOT, all.size());
            merged = List.copyOf(all);
        }
        int added = merged.size() - snapshot.size();
        snapshot = merged;
        snapshotLang = fetchLang;
        LOGGER.debug("[DungeonTrain] shared-book pool refreshed: {} book(s) ({} new this fetch)",
                merged.size(), Math.max(0, added));
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
        // The relay sends `lang` today (the author's locale); `weight` it does not (it tiers by weight
        // server-side instead). Both are optional here: a missing lang means "untagged", which
        // LanguageFamily treats as English, and a missing weight defaults to 1 so every book compares
        // equally and only the translated penalty separates them.
        String lang = o.has("lang") && !o.get("lang").isJsonNull() ? o.get("lang").getAsString() : null;
        int weight = 1;
        if (o.has("weight") && o.get("weight").isJsonPrimitive()) {
            try {
                weight = Math.max(0, o.get("weight").getAsInt());
            } catch (RuntimeException ignored) {
                // non-numeric weight — keep the default
            }
        }
        return new PoolBook(id, title, author, pages, lang, weight);
    }

    /** The {@code &lang=<locale>} query fragment for language-matched delivery, or {@code ""} when blank. */
    static String langParam(String hostLang) {
        if (hostLang == null || hostLang.isBlank()) return "";
        return "&lang=" + URLEncoder.encode(hostLang, StandardCharsets.UTF_8);
    }

    /** Splittable-mix so a raw roll seed spreads uniformly across the pool index. */
    private static long mix(long seed) {
        long state = seed ^ 0x53484152454442L; // "SHAREDB"
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        state = state ^ (state >>> 31);
        return state;
    }

    /** Test/reset hook: drop the snapshot (used by unit tests and on server stop). */
    static synchronized void clear() {
        snapshot = List.of();
        snapshotLang = null;
        fetchInFlight = false;
        approvedTotal = 0;
    }
}
