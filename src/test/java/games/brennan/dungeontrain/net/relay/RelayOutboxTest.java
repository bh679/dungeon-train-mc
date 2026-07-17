package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for the durable relay outbox, exercised through its package-private test seams
 * (injected file path, single + batch {@link RelayOutbox.Sender transport}, relay base, and clock) —
 * no Minecraft runtime, no real network. Covers the durability contract: persist-before-send,
 * remove-only-on-2xx, hold-and-retry, drop non-retryable poison, in-flight dedup, and both eviction
 * bounds — now delivered through the coalesced {@code /telemetry/batch} path, plus the batch-specific
 * concerns: coalescing, batchable/non-batchable routing, and the old-relay individual fallback.
 */
class RelayOutboxTest {

    private static final String BASE = "https://relay.test";

    /** Records every single-transport send and returns a caller-controlled status synchronously. */
    private static final class RecordingSender implements RelayOutbox.Sender {
        final List<String> urls = new CopyOnWriteArrayList<>();
        volatile int status;

        RecordingSender(int status) {
            this.status = status;
        }

        @Override
        public CompletableFuture<Integer> send(String url, String body) {
            urls.add(url);
            return CompletableFuture.completedFuture(status);
        }
    }

    /**
     * Records every batch POST and synthesises the relay's per-item {@code {key,status}} response.
     * {@code batchStatus} is the HTTP status of the POST itself (200 → per-item results echoed with
     * {@code itemStatus}; anything else → that status with no body, driving hold / fallback paths).
     */
    private static final class RecordingBatchSender implements RelayOutbox.BatchSender {
        final List<String> urls = new CopyOnWriteArrayList<>();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        volatile int itemStatus;      // per-item verdict echoed for every batched line
        volatile int batchStatus = 200; // HTTP status of the batch POST itself

        RecordingBatchSender(int itemStatus) {
            this.itemStatus = itemStatus;
        }

        @Override
        public CompletableFuture<RelayOutbox.BatchResult> send(String url, String ndjson) {
            urls.add(url);
            bodies.add(ndjson);
            if (batchStatus < 200 || batchStatus >= 300) {
                return CompletableFuture.completedFuture(new RelayOutbox.BatchResult(batchStatus, null));
            }
            StringBuilder sb = new StringBuilder("{\"results\":[");
            boolean first = true;
            for (String line : ndjson.split("\n")) {
                if (line.isBlank()) continue;
                String key = JsonParser.parseString(line).getAsJsonObject().get("key").getAsString();
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"key\":\"").append(key).append("\",\"status\":").append(itemStatus).append('}');
            }
            sb.append("]}");
            return CompletableFuture.completedFuture(new RelayOutbox.BatchResult(200, sb.toString()));
        }

        int lastBatchLineCount() {
            if (bodies.isEmpty()) return 0;
            String last = bodies.get(bodies.size() - 1);
            return last.isBlank() ? 0 : last.split("\n").length;
        }
    }

    /** Hands back batch futures the test completes by hand — for exercising the in-flight state. */
    private static final class ManualBatchSender implements RelayOutbox.BatchSender {
        final List<CompletableFuture<RelayOutbox.BatchResult>> futures = new CopyOnWriteArrayList<>();
        int calls;

        @Override
        public CompletableFuture<RelayOutbox.BatchResult> send(String url, String ndjson) {
            calls++;
            CompletableFuture<RelayOutbox.BatchResult> f = new CompletableFuture<>();
            futures.add(f);
            return f;
        }
    }

    private static final RelayOutbox.Sender NOOP_SENDER = (url, body) -> CompletableFuture.completedFuture(200);
    private static final RelayOutbox.BatchSender NOOP_BATCH = (url, ndjson) ->
            CompletableFuture.completedFuture(new RelayOutbox.BatchResult(200, "{\"results\":[]}"));

    private static RelayOutbox outbox(Path file, RelayOutbox.Sender sender, RelayOutbox.BatchSender batch, LongSupplier clock) {
        Supplier<Path> fileSupplier = () -> file;
        return new RelayOutbox(fileSupplier, sender, batch, () -> BASE, clock);
    }

    // --- delivery through the batch path -------------------------------------------------------

    @Test
    void batchDeliversAndRemovesOn2xx(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        RecordingBatchSender batch = new RecordingBatchSender(200);
        RelayOutbox box = outbox(file, NOOP_SENDER, batch, () -> 1_000L);

        box.enqueue("/telemetry/run-summary", "{\"a\":1}");

        assertEquals(1, batch.urls.size(), "one batch delivery attempt");
        assertEquals(BASE + "/telemetry/batch", batch.urls.get(0), "coalesced onto /telemetry/batch");
        assertEquals(0, box.pendingCount(), "a per-item 2xx removes the item");
    }

    @Test
    void heldWhenOfflineAndSurvivesReload(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        RecordingBatchSender offline = new RecordingBatchSender(200);
        offline.batchStatus = -1; // network failure of the batch POST
        RelayOutbox box = outbox(file, NOOP_SENDER, offline, () -> 1_000L);

        box.enqueue("/telemetry/world-info", "{\"seed\":42}");
        box.enqueue("/telemetry/death-equipment", "{\"slot\":\"head\"}");

        assertEquals(2, box.pendingCount(), "offline items stay queued");
        assertTrue(Files.exists(file), "queue persisted to disk before delivery");

        RelayOutbox reloaded = outbox(file, NOOP_SENDER, offline, () -> 1_000L);
        assertEquals(2, reloaded.pendingCount(), "queued items survive a restart");
    }

    @Test
    void heldItemDeliversOnLaterRetry(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        RecordingBatchSender batch = new RecordingBatchSender(200);
        batch.batchStatus = -1;
        RelayOutbox box = outbox(file, NOOP_SENDER, batch, () -> 1_000L);

        box.enqueue("/telemetry/book-read", "{\"id\":\"x\"}");
        assertEquals(1, box.pendingCount(), "kept while offline");

        batch.batchStatus = 200; // relay back online
        box.flush();
        assertEquals(0, box.pendingCount(), "delivered on the retry flush");
    }

    @Test
    void perItemPoisonDroppedButBatchLevelServerErrorKept(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        // Per-item 400 (a poison record the relay rejected) → dropped, not retried forever.
        RelayOutbox poison = outbox(file, NOOP_SENDER, new RecordingBatchSender(400), () -> 1_000L);
        poison.enqueue("/telemetry/run-summary", "{\"bad\":true}");
        assertEquals(0, poison.pendingCount(), "a per-item 4xx is dropped");

        // Batch-level 503 (whole POST failed) → every item kept for retry.
        Path file2 = dir.resolve("outbox2.json");
        RecordingBatchSender transient5xx = new RecordingBatchSender(200);
        transient5xx.batchStatus = 503;
        RelayOutbox box2 = outbox(file2, NOOP_SENDER, transient5xx, () -> 1_000L);
        box2.enqueue("/telemetry/run-summary", "{\"ok\":true}");
        assertEquals(1, box2.pendingCount(), "a batch-level 5xx keeps the items");
    }

    @Test
    void inFlightBatchIsNotDispatchedTwice(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        ManualBatchSender batch = new ManualBatchSender();
        RelayOutbox box = outbox(file, NOOP_SENDER, batch, () -> 1_000L);

        box.enqueue("/telemetry/world-info", "{\"seed\":1}"); // enqueue triggers the first flush
        assertEquals(1, batch.calls, "dispatched once");
        assertEquals(1, box.pendingCount(), "still pending — delivery not yet confirmed");

        box.flush(); // a concurrent flush must not re-dispatch the in-flight key
        assertEquals(1, batch.calls, "not dispatched again while in flight");

        // Complete the held future with a per-item 2xx for the one queued key.
        String ndjson = "{\"results\":[{\"key\":\"" + queuedKey(file) + "\",\"status\":200}]}";
        batch.futures.get(0).complete(new RelayOutbox.BatchResult(200, ndjson));
        assertEquals(0, box.pendingCount(), "removed after the 2xx completes");
    }

    // --- coalescing + routing --------------------------------------------------------------------

    @Test
    void coalescesMultipleQueuedItemsIntoOneBatch(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        RecordingBatchSender batch = new RecordingBatchSender(200);
        batch.batchStatus = -1; // accumulate offline
        RelayOutbox box = outbox(file, NOOP_SENDER, batch, () -> 1_000L);

        box.enqueue("/telemetry/book-read", "{\"i\":1}");
        box.enqueue("/telemetry/run-summary", "{\"i\":2}");
        box.enqueue("/telemetry/death", "{\"i\":3}");
        assertEquals(3, box.pendingCount(), "three held offline");

        batch.batchStatus = 200;
        box.flush(); // ONE flush delivers all three in a single batch POST
        assertEquals(3, batch.lastBatchLineCount(), "all three coalesced into one NDJSON batch");
        assertEquals(0, box.pendingCount(), "all delivered");
    }

    @Test
    void runBatchedCoalescesOnlineBurstIntoOneBatch(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        RecordingBatchSender batch = new RecordingBatchSender(200); // ONLINE: each bare enqueue delivers on its own
        RelayOutbox box = outbox(file, NOOP_SENDER, batch, () -> 1_000L);

        // The exact per-death burst: five batchable telemetry signals enqueued back-to-back.
        box.runBatched(() -> {
            box.enqueue("/telemetry/death", "{\"i\":1}");
            box.enqueue("/telemetry/death-equipment", "{\"i\":2}");
            box.enqueue("/telemetry/run-summary", "{\"i\":3}");
            box.enqueue("/telemetry/death-detail", "{\"i\":4}");
            box.enqueue("/telemetry/death-inventory", "{\"i\":5}");
        });

        assertEquals(1, batch.urls.size(), "the whole death burst coalesces into ONE batch POST");
        assertEquals(5, batch.lastBatchLineCount(), "all five death signals rode a single NDJSON batch");
        assertEquals(0, box.pendingCount(), "all delivered on the single trailing flush");
    }

    @Test
    void onlineEnqueuesWithoutBatchScopeFanOutOneRequestEach(@TempDir Path dir) {
        // Documents the behaviour runBatched fixes: absent a batch window, each online enqueue flushes
        // immediately and reserves its item in-flight, so a synchronous burst still fans out per item.
        Path file = dir.resolve("outbox.json");
        RecordingBatchSender batch = new RecordingBatchSender(200);
        RelayOutbox box = outbox(file, NOOP_SENDER, batch, () -> 1_000L);

        box.enqueue("/telemetry/death", "{\"i\":1}");
        box.enqueue("/telemetry/death-equipment", "{\"i\":2}");

        assertEquals(2, batch.urls.size(), "each online enqueue flushes on its own -> one batch POST each");
        assertEquals(0, box.pendingCount(), "both delivered");
    }

    @Test
    void runBatchedFlushesEvenWhenWorkThrows(@TempDir Path dir) {
        // The trailing flush runs in a finally, so an exception mid-burst never strands a queued item.
        Path file = dir.resolve("outbox.json");
        RecordingBatchSender batch = new RecordingBatchSender(200);
        RelayOutbox box = outbox(file, NOOP_SENDER, batch, () -> 1_000L);

        assertThrows(RuntimeException.class, () -> box.runBatched(() -> {
            box.enqueue("/telemetry/death", "{\"i\":1}");
            throw new RuntimeException("boom");
        }));

        assertEquals(1, batch.urls.size(), "the trailing flush still ran despite the throw");
        assertEquals(0, box.pendingCount(), "the queued item was delivered, not stranded");
    }

    @Test
    void nonBatchablePathsUseTheSingleTransport(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        RecordingSender single = new RecordingSender(-1);     // offline to accumulate
        RecordingBatchSender batch = new RecordingBatchSender(200);
        batch.batchStatus = -1;
        RelayOutbox box = outbox(file, single, batch, () -> 1_000L);

        box.enqueue("/telemetry/book-read", "{\"b\":1}");   // batchable
        box.enqueue("/reincarnations/used", "{\"id\":7}");   // NOT batchable → single transport

        single.status = 200;
        batch.batchStatus = 200;
        box.flush();

        assertTrue(single.urls.contains(BASE + "/reincarnations/used"), "stateful path stays one-per-item");
        assertTrue(batch.urls.contains(BASE + "/telemetry/batch"), "telemetry path is coalesced");
        assertEquals(0, box.pendingCount(), "both delivered");
    }

    @Test
    void fallsBackToIndividualWhenRelayLacksBatchEndpoint(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        RecordingSender single = new RecordingSender(200);
        RecordingBatchSender oldRelay = new RecordingBatchSender(200);
        oldRelay.batchStatus = 404; // relay predates /telemetry/batch
        RelayOutbox box = outbox(file, single, oldRelay, () -> 1_000L);

        box.enqueue("/telemetry/book-read", "{\"id\":\"x\"}");

        assertEquals(1, oldRelay.urls.size(), "tried the batch endpoint once");
        assertTrue(single.urls.contains(BASE + "/telemetry/book-read"), "fell back to the individual endpoint");
        assertEquals(0, box.pendingCount(), "delivered via the fallback");
    }

    // --- eviction / robustness (unchanged semantics) ---------------------------------------------

    @Test
    void evictsItemsPastMaxAge(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        AtomicLong clock = new AtomicLong(1_000L);
        RecordingBatchSender offline = new RecordingBatchSender(200);
        offline.batchStatus = -1;
        RelayOutbox box = outbox(file, NOOP_SENDER, offline, clock::get);

        box.enqueue("/telemetry/run-summary", "{\"old\":true}");
        assertEquals(1, box.pendingCount());

        clock.set(1_000L + RelayOutbox.MAX_AGE_MS + 1);
        box.flush(); // flush evicts stale entries before attempting delivery
        assertEquals(0, box.pendingCount(), "stale telemetry is evicted rather than replayed");
    }

    @Test
    void evictsOldestPastMaxItems(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        RecordingBatchSender offline = new RecordingBatchSender(200);
        offline.batchStatus = -1;
        RelayOutbox box = outbox(file, NOOP_SENDER, offline, () -> 1_000L);

        for (int i = 0; i < RelayOutbox.MAX_ITEMS + 5; i++) {
            box.enqueue("/telemetry/book-read", "{\"i\":" + i + "}");
        }
        assertEquals(RelayOutbox.MAX_ITEMS, box.pendingCount(), "queue is bounded; oldest evicted");
    }

    @Test
    void corruptFileLoadsEmptyAndNeverThrows(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("outbox.json");
        Files.writeString(file, "{ this is not json ]");
        RelayOutbox box = outbox(file, NOOP_SENDER, NOOP_BATCH, () -> 1_000L);
        assertEquals(0, box.pendingCount(), "a corrupt file yields an empty queue");
    }

    @Test
    void ignoresBlankInput(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        RelayOutbox box = outbox(file, NOOP_SENDER, NOOP_BATCH, () -> 1_000L);
        box.enqueue(null, "{}");
        box.enqueue("/telemetry/run-summary", "");
        box.enqueue("  ", "{}");
        assertEquals(0, box.pendingCount(), "null/blank path or body is a no-op");
    }

    @Test
    void retryabilityClassification() {
        assertTrue(RelayOutbox.isRetryable(-1), "network failure");
        assertTrue(RelayOutbox.isRetryable(408), "request timeout");
        assertTrue(RelayOutbox.isRetryable(429), "rate limited");
        assertTrue(RelayOutbox.isRetryable(500), "server error");
        assertTrue(RelayOutbox.isRetryable(503), "service unavailable");
        assertFalse(RelayOutbox.isRetryable(400), "bad request is permanent");
        assertFalse(RelayOutbox.isRetryable(404), "not found is permanent");
    }

    /** Read the single queued item's key straight from the persisted outbox file (for the manual-future test). */
    private static String queuedKey(Path file) {
        try {
            String json = Files.readString(file);
            return JsonParser.parseString(json).getAsJsonObject()
                    .getAsJsonArray("pending").get(0).getAsJsonObject().get("key").getAsString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
