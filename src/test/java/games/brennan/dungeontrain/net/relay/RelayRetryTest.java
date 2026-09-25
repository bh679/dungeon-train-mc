package games.brennan.dungeontrain.net.relay;

import games.brennan.dungeontrain.net.relay.RelayRetry.Outcome;
import games.brennan.dungeontrain.net.relay.RelayRetry.Transport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retry schedule, driven with canned outcomes — no socket. What is being pinned: only a
 * transport failure earns another attempt, each attempt gets its own budget in order, and the run
 * reports the last attempt and how many were made.
 */
class RelayRetryTest {

    private static final List<Duration> TWO = List.of(Duration.ofSeconds(20), Duration.ofSeconds(30));

    /** A stand-in response: only {@link #statusCode()} is ever read by the code under test. */
    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status) {
        return (HttpResponse<String>) java.lang.reflect.Proxy.newProxyInstance(
                RelayRetryTest.class.getClassLoader(), new Class<?>[] {HttpResponse.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "statusCode" -> status;
                    case "toString" -> "response(" + status + ")";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static Outcome run(List<Transport> script, List<Duration> budgets, List<Duration> seen) throws Exception {
        List<Transport> queue = new ArrayList<>(script);
        return RelayRetry.run(budgets, Duration.ZERO, budget -> {
            seen.add(budget);
            return CompletableFuture.completedFuture(queue.remove(0));
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("an answered first attempt is the whole run")
    void answeredFirstTry() throws Exception {
        List<Duration> seen = new ArrayList<>();
        Outcome out = run(List.of(Transport.of(response(200))), TWO, seen);
        assertEquals(1, out.attempts());
        assertTrue(out.last().answered());
        assertEquals(List.of(Duration.ofSeconds(20)), seen);
    }

    @Test
    @DisplayName("a transport failure is retried with the next budget")
    void transportFailureRetries() throws Exception {
        List<Duration> seen = new ArrayList<>();
        Outcome out = run(List.of(Transport.failed(new HttpTimeoutException("slow")),
                Transport.of(response(200))), TWO, seen);
        assertEquals(2, out.attempts());
        assertTrue(out.last().answered());
        assertEquals(200, out.last().resp().statusCode());
        assertEquals(TWO, seen);
    }

    @Test
    @DisplayName("out of budgets, the run resolves to the last failure and says it timed out")
    void exhaustedReportsLastFailure() throws Exception {
        List<Duration> seen = new ArrayList<>();
        Transport second = Transport.failed(new HttpTimeoutException("still slow"));
        Outcome out = run(List.of(Transport.failed(new ConnectException("refused")), second), TWO, seen);
        assertEquals(2, out.attempts());
        assertFalse(out.last().answered());
        assertSame(second, out.last());
        assertTrue(out.last().timedOut());
        assertEquals(TWO, seen);
    }

    @Test
    @DisplayName("a refused connection on the last attempt is not a timeout")
    void refusedIsNotTimeout() throws Exception {
        Outcome out = run(List.of(Transport.failed(new ConnectException("refused"))),
                List.of(Duration.ofSeconds(10)), new ArrayList<>());
        assertEquals(1, out.attempts());
        assertFalse(out.last().timedOut());
        assertNull(out.last().resp());
    }

    @Test
    @DisplayName("a response the relay sent is final, whatever its status")
    void serverErrorIsNotRetried() throws Exception {
        List<Duration> seen = new ArrayList<>();
        Outcome out = run(List.of(Transport.of(response(503)), Transport.of(response(200))), TWO, seen);
        assertEquals(1, out.attempts());
        assertEquals(503, out.last().resp().statusCode());
        assertEquals(List.of(Duration.ofSeconds(20)), seen);
    }

    @Test
    @DisplayName("an attempt that throws or completes exceptionally counts as a transport failure")
    void exceptionalAttemptIsTransportFailure() throws Exception {
        List<Duration> seen = new ArrayList<>();
        Outcome out = RelayRetry.run(TWO, Duration.ZERO, budget -> {
            seen.add(budget);
            if (seen.size() == 1) throw new IllegalStateException("boom");
            return CompletableFuture.failedFuture(new HttpTimeoutException("late"));
        }).get(5, TimeUnit.SECONDS);
        assertEquals(2, out.attempts());
        assertFalse(out.last().answered());
        assertTrue(out.last().timedOut());
    }

    @Test
    @DisplayName("a wrapped timeout still reads as one")
    void wrappedTimeoutReads() {
        Transport t = Transport.failed(new RuntimeException(new HttpTimeoutException("inner")));
        assertTrue(t.timedOut());
        assertFalse(Transport.failed(null).timedOut());
        assertFalse(Transport.failed(null).answered());
    }

    @Test
    @DisplayName("no budgets is a programming error, not a silent no-op")
    void noBudgetsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RelayRetry.run(List.of(), Duration.ZERO, b -> CompletableFuture.completedFuture(null)));
    }
}
