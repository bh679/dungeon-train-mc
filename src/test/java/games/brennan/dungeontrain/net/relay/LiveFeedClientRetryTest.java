package games.brennan.dungeontrain.net.relay;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.Result;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveFeedClientRetryTest {

    private static final Result STALE = new Result(0, null, new CompletionException(new IOException("HTTP/1.1 header parser received no bytes")));

    /** Runs the retry with the given replies in order; returns the final result and counts attempts. */
    private static Result run(AtomicInteger attempts, int retries, Result... replies) {
        Queue<Result> q = new ArrayDeque<>(List.of(replies));
        AtomicInteger fresh = new AtomicInteger();
        return LiveFeedClient.withTransportRetry(client -> {
            attempts.incrementAndGet();
            return CompletableFuture.completedFuture(q.isEmpty() ? STALE : q.poll());
        }, retries, null, () -> { fresh.incrementAndGet(); return null; }).join();
    }

    @Test
    void staleConnectionIsRetriedAndSucceeds() {
        AtomicInteger n = new AtomicInteger();
        Result r = run(n, 2, STALE, new Result(200, null, null));
        assertEquals(200, r.status());
        assertEquals(2, n.get());
    }

    @Test
    void retriesStopAfterTheLimit() {
        AtomicInteger n = new AtomicInteger();
        Result r = run(n, 2, STALE, STALE, STALE, new Result(200, null, null));
        assertEquals(0, r.status());
        assertEquals(3, n.get());
    }

    @Test
    void httpStatusIsNeverRetried() {
        AtomicInteger n = new AtomicInteger();
        assertEquals(500, run(n, 2, new Result(500, null, null)).status());
        assertEquals(403, run(n, 2, new Result(403, null, null)).status());
        assertEquals(2, n.get());
    }

    @Test
    void whatCountsAsATransportFailure() {
        assertTrue(LiveFeedClient.isTransportFailure(STALE));
        assertTrue(LiveFeedClient.isTransportFailure(new Result(0, null, new HttpTimeoutException("request timed out"))));
        assertFalse(LiveFeedClient.isTransportFailure(new Result(0, null, new NoSuchFileException("seg00001.ts"))));
        assertFalse(LiveFeedClient.isTransportFailure(new Result(0, null, new IllegalArgumentException("bad uri"))));
        assertFalse(LiveFeedClient.isTransportFailure(new Result(0, null, null)));
        assertFalse(LiveFeedClient.isTransportFailure(new Result(503, null, null)));
    }
}
