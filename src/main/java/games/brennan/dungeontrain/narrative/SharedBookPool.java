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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** One approved community book, materialised from the relay's pool response. */
    public record PoolBook(int id, String title, String author, List<String> pages) {}

    /** Current immutable snapshot. Replaced wholesale by {@link #refreshAsync}; read by {@link #rollShared}. */
    private static volatile List<PoolBook> snapshot = List.of();

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
     * <p>The built stack carries NO {@link SharedBookTag} — a found book should read like any ordinary
     * written book (it is not burned, and reading it never counts as a story read). It DOES carry a
     * {@link SharedBookReadTag} pool id so a read of it can be attributed to the specific submission on
     * the data-explorer's Books page; that tag is inert to loot / burning / progression.</p>
     */
    public static ItemStack rollShared(long rollSeed) {
        List<PoolBook> pool = snapshot; // single volatile read → consistent snapshot
        if (pool.isEmpty()) return ItemStack.EMPTY;
        int index = (int) (Long.remainderUnsigned(mix(rollSeed), pool.size()));
        PoolBook book = pool.get(index);
        ItemStack stack = BookFactory.buildPlainBook(book.title(), book.author(), book.pages());
        SharedBookReadTag.stampId(stack, book.id()); // read-telemetry identity only
        return stack;
    }

    /** Whether the pool currently holds any books (cheap volatile read). */
    public static boolean isEmpty() {
        return snapshot.isEmpty();
    }

    /**
     * Fetch a fresh pool from the relay off-thread and swap in the new snapshot. No-throw; a failed or
     * slow fetch leaves the existing snapshot in place. Skips if a fetch is already in flight.
     */
    public static void refreshAsync() {
        if (fetchInFlight) return;
        fetchInFlight = true;
        try {
            String exclude = excludeCsv();
            String url = DungeonTrain.relayBaseUrl()
                    + "/books/pool?exclude=" + exclude + "&limit=" + POOL_LIMIT;
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
                            applyResponse(resp.body());
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

    /** Parse the relay JSON body, build the new snapshot, and accumulate served ids into the seen-set. */
    static void applyResponse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return;
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("ok") || !obj.get("ok").getAsBoolean()) {
            LOGGER.debug("[DungeonTrain] shared-book pool response not ok");
            return;
        }
        if (!obj.has("books") || !obj.get("books").isJsonArray()) {
            snapshot = List.of();
            return;
        }
        List<PoolBook> parsed = new ArrayList<>();
        for (JsonElement el : obj.getAsJsonArray("books")) {
            if (!el.isJsonObject()) continue;
            PoolBook book = parseBook(el.getAsJsonObject());
            if (book != null) parsed.add(book);
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
    }
}
