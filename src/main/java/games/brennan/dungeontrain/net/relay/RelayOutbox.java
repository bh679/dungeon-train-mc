package games.brennan.dungeontrain.net.relay;

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
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * Telemetry paths coalesced into a single {@code POST /telemetry/batch} on each flush instead of one
     * POST per event — {@code /telemetry/book-read} alone peaked at 1456 hits/5min. These are the pure,
     * idempotent record ingests the relay's batch endpoint accepts. Moderated / stateful paths
     * ({@code /books/submit}, {@code /narratives/submit}, {@code /deathnotes/*}, {@code /reincarnations/used})
     * are intentionally absent — they keep their own one-per-item delivery.
     */
    private static final Set<String> BATCHABLE_PATHS = Set.of(
            "/telemetry/book-read", "/telemetry/run-summary", "/telemetry/death",
            "/telemetry/world-info", "/telemetry/death-equipment", "/telemetry/death-detail",
            "/telemetry/death-inventory");
    private static final String BATCH_PATH = "/telemetry/batch";
    /** Batch-POST statuses that mean "this relay can't take the batch" → deliver the items individually. */
    private static final Set<Integer> BATCH_FALLBACK_STATUSES = Set.of(404, 405, 413, 501);

    /**
     * Delivery transport seam. Resolves to the HTTP status code, or a negative value on a network-level
     * failure (connection refused, unknown host, timeout) — both are handled by {@link #onResult}.
     * Injectable so the queue logic can be unit-tested without a network or the game.
     */
    @FunctionalInterface
    interface Sender {
        CompletableFuture<Integer> send(String url, String body);
    }

    /**
     * Batch transport seam — like {@link Sender} but exposes the response body, since {@code
     * /telemetry/batch} answers with per-item {@code {key,status}} results the outbox must read to remove
     * only the confirmed items. {@code status} is the HTTP status of the batch POST itself (negative on a
     * network failure); {@code body} is the response text (null when there is none).
     */
    @FunctionalInterface
    interface BatchSender {
        CompletableFuture<BatchResult> send(String url, String ndjson);
    }

    /** HTTP status + body of a {@code /telemetry/batch} POST. */
    record BatchResult(int status, String body) {}

    private static final RelayOutbox INSTANCE = new RelayOutbox(
            RelayOutbox::defaultFile, defaultSender(), defaultBatchSender(), DungeonTrain::relayBaseUrl,
            System::currentTimeMillis);

    public static RelayOutbox get() {
        return INSTANCE;
    }

    private final LinkedHashMap<String, Item> pending = new LinkedHashMap<>(); // key -> item (oldest first)
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();        // keys with a send in progress
    private final Supplier<Path> fileSupplier;
    private final Sender sender;
    private final BatchSender batchSender;
    private final Supplier<String> baseUrl;
    private final LongSupplier nowMs;
    private Path file;
    private boolean loaded;

    private record Item(String key, String path, String body, long createdAtMs) {}

    /** Package-private for tests — inject the file location, single transport, relay base, and clock. */
    RelayOutbox(Supplier<Path> fileSupplier, Sender sender, Supplier<String> baseUrl, LongSupplier nowMs) {
        this(fileSupplier, sender, defaultBatchSender(), baseUrl, nowMs);
    }

    /** Package-private for tests — additionally inject the batch transport. */
    RelayOutbox(Supplier<Path> fileSupplier, Sender sender, BatchSender batchSender,
                Supplier<String> baseUrl, LongSupplier nowMs) {
        this.fileSupplier = fileSupplier;
        this.sender = sender;
        this.batchSender = batchSender;
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
        // Coalesce the batchable telemetry into ONE /telemetry/batch POST; everything else (moderated /
        // stateful paths) keeps its own one-per-item delivery.
        List<Item> batch = new ArrayList<>();
        for (Item it : snapshot) {
            if (BATCHABLE_PATHS.contains(it.path())) {
                batch.add(it);
            } else {
                sendOne(base, it);
            }
        }
        if (!batch.isEmpty()) {
            sendBatch(base, batch);
        }
    }

    /** Deliver one item on the single transport (moderated/stateful paths, and the batch fallback). */
    private void sendOne(String base, Item it) {
        if (!inFlight.add(it.key())) {
            return; // a prior flush is still delivering this one
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

    /**
     * Deliver the batchable items as a single NDJSON {@code POST /telemetry/batch}. Reserves in-flight for
     * each key (skipping any a prior flush still holds), and on completion applies the relay's per-item
     * {@code {key,status}} verdicts through {@link #onResult}. A batch-level failure keeps every item for
     * the next flush; a relay that predates the endpoint (404/405/413/501) transparently falls back to
     * individual delivery, so an old relay is never wedged.
     */
    private void sendBatch(String base, List<Item> batch) {
        List<Item> toSend = new ArrayList<>();
        for (Item it : batch) {
            if (inFlight.add(it.key())) {
                toSend.add(it);
            }
        }
        if (toSend.isEmpty()) {
            return;
        }
        String url = base + BATCH_PATH;
        String ndjson = buildNdjson(toSend);
        try {
            batchSender.send(url, ndjson).whenComplete((res, t) -> {
                for (Item it : toSend) {
                    inFlight.remove(it.key());
                }
                if (t != null || res == null) {
                    return; // transport failed unexpectedly → keep all for the next flush
                }
                handleBatchResult(base, toSend, res);
            });
        } catch (Throwable t) {
            for (Item it : toSend) {
                inFlight.remove(it.key());
            }
            LOGGER.debug("[DungeonTrain] relay outbox batch send threw: {}", t.toString());
        }
    }

    /** Apply a completed batch POST: per-item verdicts on 2xx, individual fallback on 404/405/413/501, else hold. */
    private void handleBatchResult(String base, List<Item> sent, BatchResult res) {
        int status = res.status();
        if (status >= 200 && status < 300 && res.body() != null) {
            Map<String, Integer> byKey = parseBatchResults(res.body());
            for (Item it : sent) {
                Integer st = byKey.get(it.key());
                if (st != null) {
                    onResult(it, st); // reported → remove on 2xx, drop on poison, keep on transient
                }
                // an unreported key stays queued (defensive) and retries on the next flush
            }
            return;
        }
        if (BATCH_FALLBACK_STATUSES.contains(status)) {
            LOGGER.debug("[DungeonTrain] relay outbox: /telemetry/batch -> HTTP {}; delivering {} item(s) individually.",
                    status, sent.size());
            for (Item it : sent) {
                sendOne(base, it);
            }
            return;
        }
        // network failure / 5xx / 429 / other → keep everything for the next flush (in-flight already cleared)
        LOGGER.debug("[DungeonTrain] relay outbox: /telemetry/batch held {} item(s) -> HTTP {} (will retry).",
                sent.size(), status);
    }

    /** Build the NDJSON batch body — one {@code {path,key,body}} object per item (body inlined as JSON). */
    private static String buildNdjson(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            JsonObject line = new JsonObject();
            line.addProperty("path", it.path());
            line.addProperty("key", it.key());
            try {
                line.add("body", JsonParser.parseString(it.body()));
            } catch (Exception e) {
                line.addProperty("body", it.body()); // non-JSON body (shouldn't happen) → send as-is; relay 400s it
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /** Parse {@code {results:[{key,status}]}} into a key→status map. Malformed → empty (all kept for retry). */
    private static Map<String, Integer> parseBatchResults(String body) {
        Map<String, Integer> out = new HashMap<>();
        try {
            JsonElement arr = JsonParser.parseString(body).getAsJsonObject().get("results");
            if (arr != null && arr.isJsonArray()) {
                for (JsonElement el : arr.getAsJsonArray()) {
                    if (el == null || !el.isJsonObject()) {
                        continue;
                    }
                    JsonObject o = el.getAsJsonObject();
                    if (o.has("key") && o.get("key").isJsonPrimitive() && o.has("status") && o.get("status").isJsonPrimitive()) {
                        out.put(o.get("key").getAsString(), o.get("status").getAsInt());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] relay outbox: unparseable batch results; holding all. {}", e.toString());
        }
        return out;
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
        // Pin HTTP/1.1: the default h2c upgrade over plaintext http:// is dropped by a bare-Node
        // localhost relay (breaks local 127.0.0.1 testing). Harmless in prod — Apache proxies
        // HTTP/1.1 to the origin regardless. Matches the sibling relay reporters.
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
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

    private static BatchSender defaultBatchSender() {
        // Same HTTP/1.1 pinning as defaultSender(); NDJSON body, and the response body is kept so the
        // per-item {key,status} verdicts can be applied. Network failure → status -1, null body.
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        return (url, ndjson) -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/x-ndjson")
                    .POST(HttpRequest.BodyPublishers.ofString(ndjson))
                    .build();
            return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenApply(resp -> new BatchResult(resp.statusCode(), resp.body()))
                    .exceptionally(e -> {
                        LOGGER.debug("[DungeonTrain] relay outbox batch POST {} failed: {}", url, e.toString());
                        return new BatchResult(-1, null);
                    });
        };
    }
}
