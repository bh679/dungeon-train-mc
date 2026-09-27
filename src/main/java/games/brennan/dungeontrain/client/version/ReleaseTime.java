package games.brennan.dungeontrain.client.version;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * When a release was published, from the GitHub release for its tag ({@code published_at}).
 * Called once per announcement, so the 60/hour unauthenticated API limit is never a concern.
 *
 * <p>No-throw: every failure completes with {@code null}, and the notice then goes out without
 * its "dropped N ago" clause rather than being held back.</p>
 */
final class ReleaseTime {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String DEFAULT_BASE = "https://api.github.com/repos/bh679/dungeon-train-mc/releases/tags/";

    /** Gate 2 seam — base URL the tag ({@code v<ver>}) is appended to. */
    static final String BASE_PROPERTY = "dungeontrain.releaseApiUrl";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .build();

    private ReleaseTime() {}

    /** Epoch millis the release {@code v<version>} was published, or {@code null}. */
    static CompletableFuture<Long> fetchPublishedAt(String version, String modVersion) {
        String base = System.getProperty(BASE_PROPERTY, DEFAULT_BASE);
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(base + "v" + version))
                .header("User-Agent", "DungeonTrain-Mod/" + modVersion)
                .header("Accept", "application/vnd.github+json")
                .timeout(TIMEOUT)
                .GET()
                .build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(null);
        }
        return CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() != 200) {
                    LOGGER.debug("Update notice: release lookup returned HTTP {}", resp.statusCode());
                    return null;
                }
                return parsePublishedAt(resp.body());
            })
            .exceptionally(t -> {
                LOGGER.debug("Update notice: release lookup failed: {}", t.toString());
                return null;
            });
    }

    /** {@code published_at} of a GitHub release body as epoch millis, or {@code null}. */
    static Long parsePublishedAt(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return null;
            JsonElement at = root.getAsJsonObject().get("published_at");
            if (at == null || !at.isJsonPrimitive()) return null;
            return Instant.parse(at.getAsString().trim()).toEpochMilli();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Time since publication at {@code nowMs}; clock skew that puts it in the future clamps to zero. */
    static Duration since(long publishedAtMs, long nowMs) {
        return Duration.ofMillis(Math.max(0L, nowMs - publishedAtMs));
    }
}
