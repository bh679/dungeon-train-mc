package games.brennan.dungeontrain.net.relay;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for the durable relay outbox, exercised through its package-private test seams
 * (injected file path, {@link RelayOutbox.Sender transport}, relay base, and clock) — no Minecraft
 * runtime, no real network. Covers the durability contract: persist-before-send, remove-only-on-2xx,
 * hold-and-retry on failure, drop non-retryable poison, in-flight dedup, and both eviction bounds.
 */
class RelayOutboxTest {

    private static final String BASE = "https://relay.test";

    /** Records every send and returns a caller-controlled status synchronously. */
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

    /** Hands back futures the test completes by hand — for exercising the in-flight state. */
    private static final class ManualSender implements RelayOutbox.Sender {
        final List<CompletableFuture<Integer>> futures = new CopyOnWriteArrayList<>();
        int calls;

        @Override
        public CompletableFuture<Integer> send(String url, String body) {
            calls++;
            CompletableFuture<Integer> f = new CompletableFuture<>();
            futures.add(f);
            return f;
        }
    }

    private static RelayOutbox outbox(Path file, RelayOutbox.Sender sender, LongSupplier clock) {
        Supplier<Path> fileSupplier = () -> file;
        return new RelayOutbox(fileSupplier, sender, () -> BASE, clock);
    }

    @Test
    void deliversAndRemovesOn2xx(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        RecordingSender sender = new RecordingSender(204);
        RelayOutbox box = outbox(file, sender, () -> 1_000L);

        box.enqueue("/telemetry/run-summary", "{\"a\":1}");

        assertEquals(1, sender.urls.size(), "one delivery attempt");
        assertEquals(BASE + "/telemetry/run-summary", sender.urls.get(0), "path resolved against base at flush");
        assertEquals(0, box.pendingCount(), "a 2xx removes the item");
    }

    @Test
    void heldWhenOfflineAndSurvivesReload(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        RecordingSender offline = new RecordingSender(-1); // network failure
        RelayOutbox box = outbox(file, offline, () -> 1_000L);

        box.enqueue("/telemetry/world-info", "{\"seed\":42}");
        box.enqueue("/telemetry/death-equipment", "{\"slot\":\"head\"}");

        assertEquals(2, box.pendingCount(), "offline items stay queued");
        assertTrue(Files.exists(file), "queue persisted to disk before delivery");

        // A fresh instance over the same file loads what the first persisted.
        RelayOutbox reloaded = outbox(file, offline, () -> 1_000L);
        assertEquals(2, reloaded.pendingCount(), "queued items survive a restart");
    }

    @Test
    void heldItemDeliversOnLaterRetry(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        RecordingSender sender = new RecordingSender(-1);
        RelayOutbox box = outbox(file, sender, () -> 1_000L);

        box.enqueue("/telemetry/book-read", "{\"id\":\"x\"}");
        assertEquals(1, box.pendingCount(), "kept while offline");

        sender.status = 200; // relay back online
        box.flush();
        assertEquals(0, box.pendingCount(), "delivered on the retry flush");
    }

    @Test
    void nonRetryableStatusIsDroppedButServerErrorKept(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        RelayOutbox poison = outbox(file, new RecordingSender(400), () -> 1_000L);
        poison.enqueue("/telemetry/run-summary", "{\"bad\":true}");
        assertEquals(0, poison.pendingCount(), "a permanent 4xx is dropped, not retried forever");

        Path file2 = dir.resolve("outbox2.json");
        RelayOutbox transient5xx = outbox(file2, new RecordingSender(503), () -> 1_000L);
        transient5xx.enqueue("/telemetry/run-summary", "{\"ok\":true}");
        assertEquals(1, transient5xx.pendingCount(), "a 5xx is kept for retry");
    }

    @Test
    void inFlightItemIsNotDispatchedTwice(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        ManualSender sender = new ManualSender();
        RelayOutbox box = outbox(file, sender, () -> 1_000L);

        box.enqueue("/telemetry/world-info", "{\"seed\":1}"); // enqueue triggers the first flush
        assertEquals(1, sender.calls, "dispatched once");
        assertEquals(1, box.pendingCount(), "still pending — delivery not yet confirmed");

        box.flush(); // a concurrent flush must not re-dispatch the in-flight key
        assertEquals(1, sender.calls, "not dispatched again while in flight");

        sender.futures.get(0).complete(200); // relay confirms
        assertEquals(0, box.pendingCount(), "removed after the 2xx completes");
    }

    @Test
    void evictsItemsPastMaxAge(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        AtomicLong clock = new AtomicLong(1_000L);
        RelayOutbox box = outbox(file, new RecordingSender(-1), clock::get);

        box.enqueue("/telemetry/run-summary", "{\"old\":true}");
        assertEquals(1, box.pendingCount());

        clock.set(1_000L + RelayOutbox.MAX_AGE_MS + 1);
        box.flush(); // flush evicts stale entries before attempting delivery
        assertEquals(0, box.pendingCount(), "stale telemetry is evicted rather than replayed");
    }

    @Test
    void evictsOldestPastMaxItems(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        RelayOutbox box = outbox(file, new RecordingSender(-1), () -> 1_000L);

        for (int i = 0; i < RelayOutbox.MAX_ITEMS + 5; i++) {
            box.enqueue("/telemetry/book-read", "{\"i\":" + i + "}");
        }
        assertEquals(RelayOutbox.MAX_ITEMS, box.pendingCount(), "queue is bounded; oldest evicted");
    }

    @Test
    void corruptFileLoadsEmptyAndNeverThrows(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("outbox.json");
        Files.writeString(file, "{ this is not json ]");
        RelayOutbox box = outbox(file, new RecordingSender(200), () -> 1_000L);
        assertEquals(0, box.pendingCount(), "a corrupt file yields an empty queue");
    }

    @Test
    void ignoresBlankInput(@TempDir Path dir) {
        Path file = dir.resolve("outbox.json");
        RelayOutbox box = outbox(file, new RecordingSender(-1), () -> 1_000L);
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
}
