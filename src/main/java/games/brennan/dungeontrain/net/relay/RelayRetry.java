package games.brennan.dungeontrain.net.relay;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Drives a relay call through a schedule of attempts, each with its own time budget.
 *
 * <p>Pure on purpose: it knows nothing about HTTP beyond the shape of one attempt's outcome, so the
 * schedule — how many tries, how long each may take, how long to pause between them — can be tested
 * without a socket. {@link SharedCarriageClient} supplies the attempt; this decides whether to ask
 * again.</p>
 *
 * <p>Only a <b>transport</b> failure earns another attempt: a timeout, a refused connection, a reset
 * mid-body. A response the relay actually sent — 4xx, 5xx, anything — is final however unwelcome,
 * because a bad payload stays bad and a 5xx is the relay saying it looked and could not, not that it
 * never heard. The same rule the modpack upload retry keeps ({@code scripts/modpack/lib/upload-retry.sh}).</p>
 */
public final class RelayRetry {

    /**
     * One attempt's outcome: the response when the relay answered, else the transport error that
     * stopped it. Exactly one of the two is non-null.
     */
    public record Transport(HttpResponse<String> resp, Throwable error) {
        /** Whether the relay answered at all — an HTTP status of any kind counts. */
        public boolean answered() {
            return resp != null;
        }

        /** Whether the failure was the clock rather than the wire. */
        public boolean timedOut() {
            return error != null && (error instanceof java.net.http.HttpTimeoutException
                    || error.getCause() instanceof java.net.http.HttpTimeoutException);
        }

        public static Transport of(HttpResponse<String> resp) {
            return new Transport(resp, null);
        }

        public static Transport failed(Throwable error) {
            return new Transport(null, error == null ? new IllegalStateException("no response") : error);
        }
    }

    /** What a whole run resolved to: the last attempt's outcome, plus how many were made. */
    public record Outcome(Transport last, int attempts) {}

    private RelayRetry() {}

    /**
     * Run {@code attempt} once per budget in {@code budgets}, stopping at the first answered attempt.
     *
     * <p>Between a transport failure and the next attempt the run waits {@code pause} on the common
     * pool's delayed executor — no thread is held. A zero pause runs the next attempt immediately,
     * which is what the tests use.</p>
     *
     * @param budgets one entry per attempt, in order — the time each may take. Must not be empty.
     * @param pause   how long to wait after a transport failure before the next attempt
     * @param attempt performs one call within the given budget; must never complete exceptionally
     *                (wrap failures into {@link Transport#failed})
     */
    public static CompletableFuture<Outcome> run(List<Duration> budgets, Duration pause,
                                                 Function<Duration, CompletableFuture<Transport>> attempt) {
        if (budgets == null || budgets.isEmpty()) {
            throw new IllegalArgumentException("at least one attempt budget is required");
        }
        return step(budgets, pause, attempt, 0);
    }

    private static CompletableFuture<Outcome> step(List<Duration> budgets, Duration pause,
                                                   Function<Duration, CompletableFuture<Transport>> attempt,
                                                   int index) {
        CompletableFuture<Transport> call;
        try {
            call = attempt.apply(budgets.get(index));
        } catch (Throwable t) {
            call = CompletableFuture.completedFuture(Transport.failed(t));
        }
        return call
                .exceptionally(Transport::failed)
                .thenCompose(result -> {
                    int made = index + 1;
                    if (result == null) result = Transport.failed(null);
                    if (result.answered() || made >= budgets.size()) {
                        return CompletableFuture.completedFuture(new Outcome(result, made));
                    }
                    return afterPause(pause).thenCompose(v -> step(budgets, pause, attempt, made));
                });
    }

    /** A future that completes after {@code pause} — immediately for a zero or negative pause. */
    private static CompletableFuture<Void> afterPause(Duration pause) {
        long ms = pause == null ? 0L : pause.toMillis();
        if (ms <= 0L) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> { },
                CompletableFuture.delayedExecutor(ms, TimeUnit.MILLISECONDS));
    }
}
