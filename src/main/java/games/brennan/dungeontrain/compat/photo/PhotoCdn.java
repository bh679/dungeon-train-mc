package games.brennan.dungeontrain.compat.photo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Photo and album pictures from the relay's CDN copy, falling back to the relay.
 *
 * <p>The relay's pool, lease, album and tributes JSON carry an {@code image} (albums:
 * {@code url}) on Cloudflare for every picture it has mirrored. Fetching that instead of the relay's
 * own image route keeps the bytes off the relay box, whose bandwidth is billed. The relay route still
 * answers exactly as before, so a picture with no CDN URL, a CDN answer that isn't a 200, or a CDN that
 * can't be reached at all falls back to it. Older relays send no URL, so nothing changes there.
 *
 * <p>Only https URLs on {@link #HOST} are followed: the JSON comes from the relay, but a URL is never
 * trusted beyond the one host it is meant to name. After {@link #BREAKER_FAILURES} failures in a row (a
 * blocked or broken CDN) the CDN is skipped for {@link #BREAKER_OPEN}, so photo downloads, which run one
 * after another, don't each wait out a timeout first.
 */
public final class PhotoCdn {
    private static final Logger LOGGER = LoggerFactory.getLogger(PhotoCdn.class);

    /** The one host a CDN picture URL may name. */
    public static final String HOST = "photos.dungeon.training";
    static final Duration CDN_TIMEOUT = Duration.ofSeconds(5);
    static final int BREAKER_FAILURES = 3;
    static final Duration BREAKER_OPEN = Duration.ofMinutes(10);

    private static final AtomicInteger failuresInARow = new AtomicInteger();
    private static final AtomicLong skipUntilMs = new AtomicLong();

    private PhotoCdn() {}

    /** Whether {@code url} is a CDN picture URL this jar will fetch. */
    public static boolean acceptable(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && HOST.equalsIgnoreCase(uri.getHost())
                    && uri.getPort() == -1 && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The {@code image} (or {@code url}) string off a relay row, or "" when it has none. */
    public static String urlOf(com.google.gson.JsonObject row, String field) {
        if (row == null || !row.has(field) || !row.get(field).isJsonPrimitive()) return "";
        String url = row.get(field).getAsString();
        return acceptable(url) ? url : "";
    }

    static boolean breakerOpen(long nowMs) {
        return nowMs < skipUntilMs.get();
    }

    static void recordCdn(boolean ok, long nowMs) {
        if (ok) {
            failuresInARow.set(0);
            return;
        }
        if (failuresInARow.incrementAndGet() >= BREAKER_FAILURES) {
            failuresInARow.set(0);
            skipUntilMs.set(nowMs + BREAKER_OPEN.toMillis());
            LOGGER.info("[DungeonTrain] Photo CDN unreachable; fetching pictures from the relay for {} min", BREAKER_OPEN.toMinutes());
        }
    }

    /** Tests only. */
    static void resetBreaker() {
        failuresInARow.set(0);
        skipUntilMs.set(0);
    }

    private static boolean useCdn(String cdnUrl, Predicate<String> accept) {
        return accept.test(cdnUrl) && !breakerOpen(System.currentTimeMillis());
    }

    /**
     * Whether a CDN answer is the picture: a 200 with an image type. Anything else that is not a 404 (the
     * picture was just removed) counts against the CDN: a 403/429 from a bot check or a challenge page would
     * otherwise cost every photo a wasted round trip without ever tripping the breaker.
     */
    static boolean isPicture(HttpResponse<?> resp) {
        return resp.statusCode() == 200
                && resp.headers().firstValue("content-type").orElse("").toLowerCase(java.util.Locale.ROOT).startsWith("image/");
    }

    static boolean cdnHealthy(HttpResponse<?> resp) {
        return isPicture(resp) || resp.statusCode() == 404;
    }

    private static HttpRequest get(String url, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
    }

    /**
     * Blocking: the picture from {@code cdnUrl} when that answers 200, else from {@code relayUrl}. A 404
     * from the CDN (the picture was just removed) is not a CDN failure; the relay then answers for itself.
     */
    public static HttpResponse<byte[]> send(HttpClient http, String cdnUrl, String relayUrl, Duration timeout)
            throws java.io.IOException, InterruptedException {
        return send(http, cdnUrl, relayUrl, timeout, PhotoCdn::acceptable);
    }

    /** {@link #send} with the URL check swappable, so tests can point the "CDN" at a local server. */
    static HttpResponse<byte[]> send(HttpClient http, String cdnUrl, String relayUrl, Duration timeout, Predicate<String> accept)
            throws java.io.IOException, InterruptedException {
        if (useCdn(cdnUrl, accept)) {
            try {
                HttpResponse<byte[]> cdn = http.send(get(cdnUrl, CDN_TIMEOUT), HttpResponse.BodyHandlers.ofByteArray());
                recordCdn(cdnHealthy(cdn), System.currentTimeMillis());
                if (isPicture(cdn)) return cdn;
            } catch (java.io.IOException e) {
                recordCdn(false, System.currentTimeMillis());
                LOGGER.debug("[DungeonTrain] Photo CDN fetch failed, using the relay: {}", e.toString());
            }
        }
        return http.send(get(relayUrl, timeout), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** {@link #send}, asynchronously. */
    public static CompletableFuture<HttpResponse<byte[]>> sendAsync(HttpClient http, String cdnUrl, String relayUrl, Duration timeout) {
        if (!useCdn(cdnUrl, PhotoCdn::acceptable)) return http.sendAsync(get(relayUrl, timeout), HttpResponse.BodyHandlers.ofByteArray());
        return http.sendAsync(get(cdnUrl, CDN_TIMEOUT), HttpResponse.BodyHandlers.ofByteArray())
                .handle((cdn, error) -> {
                    recordCdn(error == null && cdnHealthy(cdn), System.currentTimeMillis());
                    return error == null && isPicture(cdn) ? cdn : null;
                })
                .thenCompose(cdn -> cdn != null
                        ? CompletableFuture.completedFuture(cdn)
                        : http.sendAsync(get(relayUrl, timeout), HttpResponse.BodyHandlers.ofByteArray()));
    }
}
