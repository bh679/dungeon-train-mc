package games.brennan.dungeontrain.util;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

/**
 * Logs only the first {@code max} occurrences of a recurring error, then goes silent — for
 * hot paths (worldgen biome sampling runs per-quart on worker threads) where a persistent
 * fault would otherwise either flood the log or, worse, be swallowed silently.
 *
 * <p>Thread-safe via an {@link AtomicInteger}; counts are per-JVM-session (static fields at
 * the call sites), which is acceptable for diagnostics.</p>
 */
public final class LogFirstN {

    private final AtomicInteger count = new AtomicInteger();
    private final int max;

    public LogFirstN(int max) {
        this.max = max;
    }

    /** Log the first {@code max} occurrences (with throwable); the last logged line notes suppression. */
    public void error(Logger logger, String message, Throwable t) {
        int n = count.incrementAndGet();
        if (n <= max) {
            logger.error("{} (occurrence {}/{}{})", message, n, max,
                    n == max ? "; suppressing further reports" : "", t);
        }
    }
}
