package games.brennan.dungeontrain.client.version;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Reads the newest released version from the repo's NeoForge {@code update.json} — the same file
 * {@code updateJSONURL} in mods.toml points at, rewritten by {@code release.yml} on every release.
 * Served from raw.githubusercontent.com: keyless, CDN-cached, and free of the 60/hour GitHub API
 * limit that {@link GitHubLatestReleaseFetcher} lives under, which is what makes it safe to poll.
 *
 * <p>No-throw: every failure completes the future with {@code null}.</p>
 */
final class UpdateFeed {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String DEFAULT_URL =
        "https://raw.githubusercontent.com/bh679/dungeon-train-mc/main/update.json";

    /** Gate 2 seam — point the poll at a local file. Also lets the notice run on dev builds. */
    static final String URL_PROPERTY = "dungeontrain.updateFeedUrl";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(TIMEOUT)
        .build();

    private UpdateFeed() {}

    static String url() {
        String override = System.getProperty(URL_PROPERTY);
        return override == null || override.isBlank() ? DEFAULT_URL : override;
    }

    /** Latest version string (no {@code v}), or {@code null} on any failure. */
    static CompletableFuture<String> fetchLatest(String mcVersion, String modVersion) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(url()))
                .header("User-Agent", "DungeonTrain-Mod/" + modVersion)
                .header("Cache-Control", "no-cache")
                .timeout(TIMEOUT)
                .GET()
                .build();
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Update notice: bad feed URL {}", url());
            return CompletableFuture.completedFuture(null);
        }
        return CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() != 200) {
                    LOGGER.debug("Update notice: feed returned HTTP {}", resp.statusCode());
                    return null;
                }
                return parseLatest(resp.body(), mcVersion);
            })
            .exceptionally(t -> {
                LOGGER.debug("Update notice: feed request failed: {}", t.toString());
                return null;
            });
    }

    /** {@code promos["<mc>-latest"]} from an update.json body, or {@code null} if absent/malformed. */
    static String parseLatest(String body, String mcVersion) {
        if (body == null || body.isBlank() || mcVersion == null) return null;
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return null;
            JsonObject promos = root.getAsJsonObject().getAsJsonObject("promos");
            if (promos == null) return null;
            JsonElement latest = promos.get(mcVersion + "-latest");
            if (latest == null || !latest.isJsonPrimitive()) return null;
            String v = SemverCompare.stripV(latest.getAsString().trim());
            return v.isEmpty() ? null : v;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
