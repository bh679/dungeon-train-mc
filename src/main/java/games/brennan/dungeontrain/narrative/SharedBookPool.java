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
import java.util.List;

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

    // Pin HTTP/1.1: the relay is a cleartext-capable Node server; Java's default HTTP/2 client can't
    // h2c-upgrade over plaintext http:// (breaks local 127.0.0.1 testing). Harmless in prod — Apache
    // proxies HTTP/1.1 to the origin regardless. Mirrors BookStatsClient.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * One approved community book, materialised from the relay's pool response. {@code weight} is the
     * relay-supplied SELECTION weight (admin weight + 1, floored at 0), driving the weighted pick in
     * {@link #rollShared} — a book with weight {@code w} is {@code w}× as likely to be chosen as a
     * weight-1 book. Defaults to 1 (uniform) when an older relay omits the field.
     */
    public record PoolBook(int id, String title, String author, List<String> pages, int weight) {}

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

    /** Prevents overlapping in-flight fetches (a slow relay shouldn't stack requests every tick). */
    private static volatile boolean fetchInFlight = false;

    private SharedBookPool() {}

    /**
     * Pick a community book from the current snapshot — a deterministic <b>weighted</b> lottery by each
     * book's {@link PoolBook#weight()} (admin weight + 1) — and build a plain written book crediting its
     * author. Returns {@link ItemStack#EMPTY} when the snapshot is empty or every book has weight 0.
     * Deterministic per {@code rollSeed} so the same chest at the same world seed always yields the same
     * book.
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
        PoolBook book = weightedPick(pool, mix(rollSeed));
        if (book == null) return ItemStack.EMPTY;
        ItemStack stack = BookFactory.buildPlainBook(book.title(), book.author(), book.pages());
        SharedBookFoundTag.stamp(stack);               // "read a stranger's book" advancement marker
        SharedBookReadTag.stampId(stack, book.id());   // read-telemetry identity only
        LOGGER.debug("[DungeonTrain] shared-book weighted pick -> id {} \"{}\" (weight {})",
                book.id(), book.title(), book.weight());
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
     */
    public static void refreshAsync() {
        if (fetchInFlight) return;
        fetchInFlight = true;
        try {
            // No exclude filter: selection is now a per-roll WEIGHTED lottery (see rollShared), so books
            // must be free to repeat across fetches — excluding served ids would flatten the weighting to
            // roughly one-appearance-per-cycle. Each refresh pulls a fresh window of up to POOL_LIMIT.
            String url = DungeonTrain.relayBaseUrl()
                    + "/books/pool?limit=" + POOL_LIMIT;
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

    /**
     * Parse the relay JSON body and swap in the new snapshot. Because fetches no longer send an
     * {@code exclude} filter, a zero-book reply means the relay genuinely has no approved books (or none
     * with a positive selection weight) → clear the snapshot. A malformed reply keeps the last good
     * snapshot so a transient relay blip doesn't wipe loot.
     */
    static void applyResponse(String body) {
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
            snapshot = List.of();
            LOGGER.debug("[DungeonTrain] shared-book pool is empty");
            return;
        }
        // Publish an immutable snapshot (copy so no external ref can mutate it).
        snapshot = List.copyOf(parsed);
        if (LOGGER.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder();
            for (PoolBook pb : parsed) sb.append(" [id ").append(pb.id()).append(" w=").append(pb.weight()).append(']');
            LOGGER.debug("[DungeonTrain] shared-book pool refreshed: {} book(s):{}", parsed.size(), sb);
        }
    }

    /** Materialise one pool entry; returns {@code null} if it lacks the required fields. */
    static PoolBook parseBook(JsonObject o) {
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
        // Relay-supplied selection weight (admin weight + 1). Absent → 1 (uniform), so an older relay
        // that doesn't send the field degrades gracefully to today's flat behaviour.
        int weight = o.has("weight") && o.get("weight").isJsonPrimitive()
                ? Math.max(0, o.get("weight").getAsInt())
                : 1;
        return new PoolBook(id, title, author, pages, weight);
    }

    /**
     * Deterministic weighted pick over {@code pool} using an already-mixed seed: a book of weight
     * {@code w} occupies {@code w} of the {@code Σweight} slots, so it is {@code w}× as likely as a
     * weight-1 book. Returns {@code null} when the pool is empty or every weight is 0 (nothing to
     * spawn → caller falls back to a local book). Weights are floored at 0 defensively; the relay
     * already drops selection-weight-0 books. Package-private so unit tests can assert the distribution
     * without building an {@link ItemStack}.
     */
    static PoolBook weightedPick(List<PoolBook> pool, long mixedSeed) {
        if (pool.isEmpty()) return null;
        long total = 0;
        for (PoolBook b : pool) total += Math.max(0, b.weight());
        if (total <= 0) return null;
        long target = Long.remainderUnsigned(mixedSeed, total);
        for (PoolBook b : pool) {
            target -= Math.max(0, b.weight());
            if (target < 0) return b;
        }
        return pool.get(pool.size() - 1); // unreachable when total > 0, but keep the pick total
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
        fetchInFlight = false;
        approvedTotal = 0;
    }
}
