package games.brennan.dungeontrain.net.relay;
import games.brennan.dungeontrain.DtCore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Durable, server-side outbox for the relay <em>telemetry</em> POSTs (run-summary, death-equipment,
 * world-info, book-read, shared-book, echo-used), so a record survives a relay outage / offline
 * launch and is delivered on the next flush instead of being silently dropped. The telemetry
 * reporters were fire-and-forget ({@code sendAsync(...).exceptionally(log & drop)}); this is the
 * store that turns them into at-least-once delivery.
 *
 * <p>The direct client-side analogue is
 * {@link games.brennan.dungeontrain.client.chat.ChatOutbox} (title-screen menu chat). This class is
 * its server-side, endpoint-generic sibling: one file for all telemetry endpoints, keyed by a
 * relay <b>path</b> (not a full URL) so a queued item always targets the <i>current</i> build's relay
 * — {@link DungeonTrain#relayBaseUrl()} is resolved at flush time (dev vs live differ by branch).</p>
 *
 * <p>Backed by {@code dungeontrain-relay-outbox.json} in the MC config dir
 * ({@code {"pending":[{key, path, body, createdAtMs}]}}), written through atomically (tmp + rename).
 * Best-effort: a missing or corrupt file yields an empty queue and never throws into the game thread.</p>
 *
 * <p><b>Delivery is at-least-once</b>: an item is removed <i>only after</i> the relay confirms a 2xx,
 * so a record is never silently lost; the cost is a rare duplicate if the process dies after the relay
 * accepts the POST but before this persists the removal (each item carries a unique {@code key} the
 * relay could use to collapse that case). A non-retryable {@code 4xx} (a rejected/poison payload) is
 * dropped rather than retried forever; {@code 408 / 429 / 5xx} and network failures are kept for the
 * next flush. The queue is bounded by count (oldest evicted) and by age, so a permanently-offline
 * client can neither grow the file without limit nor replay weeks-stale telemetry.</p>
 */
public final class RelayOutbox {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Bounded so a permanently-offline client can't grow the file without limit (oldest evicted). */
    static final int MAX_ITEMS = 500;
    /** Stale telemetry past this age is evicted rather than replayed on a much-later reconnect. */
    static final long MAX_AGE_MS = Duration.ofDays(14).toMillis();
    private static final String FILE_NAME = "dungeontrain-relay-outbox.json";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Delivery transport seam. Resolves to the HTTP status code, or a negative value on a network-level
     * failure (connection refused, unknown host, timeout) — both are handled by {@link #onResult}.
     * Injectable so the queue logic can be unit-tested without a network or the game.
     */
    @FunctionalInterface
    interface Sender {
        CompletableFuture<Integer> send(String url, String body);
    }

    private static final RelayOutbox INSTANCE = new RelayOutbox(
            RelayOutbox::defaultFile, defaultSender(), DtCore::relayBaseUrl, System::currentTimeMillis);

    public static RelayOutbox get() {
        return INSTANCE;
    }

    private final LinkedHashMap<String, Item> pending = new LinkedHashMap<>(); // key -> item (oldest first)
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();        // keys with a send in progress
    private final Supplier<Path> fileSupplier;
    private final Sender sender;
    private final Supplier<String> baseUrl;
    private final LongSupplier nowMs;
    private Path file;
    private boolean loaded;

    private record Item(String key, String path, String body, long createdAtMs) {}

    /** Package-private for tests — inject the file location, transport, relay base, and clock. */
    RelayOutbox(Supplier<Path> fileSupplier, Sender sender, Supplier<String> baseUrl, LongSupplier nowMs) {
        this.fileSupplier = fileSupplier;
        this.sender = sender;
        this.baseUrl = baseUrl;
        this.nowMs = nowMs;
    }

    /**
     * Queue a telemetry {@code body} for relay {@code path} (e.g. {@code /telemetry/run-summary}) and
     * immediately try to deliver it. Called on the server thread; never throws. Online → the flush
     * delivers and removes it right away; offline → it stays queued for the next {@link #flush()}.
     * Callers keep their own consent gate — nothing reaches here the player hasn't consented to.
     */
    public void enqueue(String path, String body) {
        if (path == null || path.isBlank() || body == null || body.isBlank()) {
            return;
        }
        synchronized (this) {
            ensureLoaded();
            String key = UUID.randomUUID().toString(); // dedup handle + relay idempotency key
            pending.put(key, new Item(key, path, body, nowMs.getAsLong()));
            evict();
            save();
        }
        flush();
    }

    /**
     * Attempt to deliver every queued record, removing each only after the relay confirms a 2xx.
     * Unreachable relay / network failure → items stay queued. Safe to call repeatedly (on submit, on
     * the periodic tick, on server start): an in-flight key is never dispatched twice.
     */
    public void flush() {
        List<Item> snapshot;
        synchronized (this) {
            ensureLoaded();
            if (evict()) {
                save();
            }
            if (pending.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(pending.values());
        }
        String base = baseUrl.get();
        if (base == null || base.isBlank()) {
            return; // no destination resolved → hold everything for a later flush
        }
        for (Item it : snapshot) {
            if (!inFlight.add(it.key())) {
                continue; // a prior flush is still delivering this one
            }
            String url = base + it.path();
            try {
                sender.send(url, it.body()).whenComplete((status, t) -> {
                    inFlight.remove(it.key());
                    if (t == null && status != null) {
                        onResult(it, status);
                    }
                    // else: send future failed unexpectedly → keep for the next flush
                });
            } catch (Throwable t) {
                inFlight.remove(it.key());
                LOGGER.debug("[DungeonTrain] relay outbox send threw for {}: {}", it.path(), t.toString());
            }
        }
    }

    /** Test seam: number of records still awaiting delivery. */
    public synchronized int pendingCount() {
        ensureLoaded();
        return pending.size();
    }

    /** Apply the relay's verdict: 2xx delivered (remove); transient kept; other non-2xx dropped as poison. */
    private void onResult(Item it, int status) {
        if (status >= 200 && status < 300) {
            remove(it.key());
        } else if (isRetryable(status)) {
            LOGGER.debug("[DungeonTrain] relay outbox held {} -> HTTP {} (will retry).", it.path(), status);
        } else {
            LOGGER.warn("[DungeonTrain] relay outbox dropping {} -> HTTP {} (non-retryable).", it.path(), status);
            remove(it.key());
        }
    }

    /** A negative (network) result, request-timeout, rate-limit, or any 5xx is worth retrying later. */
    static boolean isRetryable(int status) {
        return status < 0 || status == 408 || status == 429 || status >= 500;
    }

    private synchronized void remove(String key) {
        if (pending.remove(key) != null) {
            save();
        }
    }

    /** Drop items past {@link #MAX_AGE_MS}, then the oldest beyond {@link #MAX_ITEMS}. Returns true if changed. */
    private boolean evict() {
        boolean changed = false;
        long cutoff = nowMs.getAsLong() - MAX_AGE_MS;
        Iterator<Item> byAge = pending.values().iterator();
        while (byAge.hasNext()) {
            if (byAge.next().createdAtMs() < cutoff) {
                byAge.remove();
                changed = true;
            }
        }
        Iterator<String> bySize = pending.keySet().iterator();
        while (pending.size() > MAX_ITEMS && bySize.hasNext()) {
            bySize.next();
            bySize.remove();
            changed = true;
        }
        return changed;
    }

    // --- persistence (best-effort) ---

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            this.file = fileSupplier.get();
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] relay outbox: could not resolve store path: {}", t.toString());
            return;
        }
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonElement arr = obj.get("pending");
            if (arr != null && arr.isJsonArray()) {
                for (JsonElement el : arr.getAsJsonArray()) {
                    if (el == null || !el.isJsonObject()) {
                        continue;
                    }
                    JsonObject o = el.getAsJsonObject();
                    String key = optString(o, "key");
                    String path = optString(o, "path");
                    String body = optString(o, "body");
                    long createdAtMs = optLong(o, "createdAtMs", nowMs.getAsLong());
                    if (key != null && path != null && !path.isBlank() && body != null && !body.isBlank()) {
                        pending.put(key, new Item(key, path, body, createdAtMs));
                    }
                }
            }
            evict();
            LOGGER.debug("[DungeonTrain] relay outbox: loaded {} queued record(s).", pending.size());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] relay outbox: failed to read {}; starting empty.", file, e);
            pending.clear();
        }
    }

    private void save() {
        Path target = this.file;
        if (target == null) {
            return;
        }
        try {
            JsonArray arr = new JsonArray();
            for (Item it : pending.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("key", it.key());
                o.addProperty("path", it.path());
                o.addProperty("body", it.body());
                o.addProperty("createdAtMs", it.createdAtMs());
                arr.add(o);
            }
            JsonObject obj = new JsonObject();
            obj.add("pending", arr);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, obj.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] relay outbox: failed to write {}.", target, e);
        }
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static long optLong(JsonObject o, String key, long fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Path defaultFile() {
        try {
            return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] relay outbox: could not resolve config dir: {}", t.toString());
            return null;
        }
    }

    private static Sender defaultSender() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        return (url, body) -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenApply(HttpResponse::statusCode)
                    .exceptionally(e -> {
                        LOGGER.debug("[DungeonTrain] relay outbox POST {} failed: {}", url, e.toString());
                        return -1;
                    });
        };
    }
}
