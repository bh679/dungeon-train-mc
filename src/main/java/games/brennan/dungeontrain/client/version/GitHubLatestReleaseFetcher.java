package games.brennan.dungeontrain.client.version;
import games.brennan.dungeontrain.platform.DtPlatform;
import games.brennan.dungeontrain.DtCore;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Performs the asynchronous HTTP fetch against the GitHub Releases API and
 * pushes the parsed result into {@link VersionCheckState}. One shared
 * single-thread daemon executor handles every fetch, which serialises
 * accidental concurrent triggers and never blocks JVM shutdown.
 *
 * <p>GitHub returns {@code 403 Forbidden} for unauthenticated requests
 * without a {@code User-Agent} — we always send one identifying the mod
 * and the running version, which also gives GitHub a way to contact the
 * project if the calls ever start misbehaving.</p>
 */
public final class GitHubLatestReleaseFetcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final URI URL = URI.create(
        "https://api.github.com/repos/bh679/dungeon-train-mc/releases/latest");

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DungeonTrain-VersionCheck");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .executor(EXECUTOR)
        .build();

    private GitHubLatestReleaseFetcher() {}

    static void fetchAsync() {
        String current = currentModVersion();
        HttpRequest req = HttpRequest.newBuilder(URL)
            .header("User-Agent", "DungeonTrain-Mod/" + current)
            .header("Accept", "application/vnd.github+json")
            .timeout(TIMEOUT)
            .GET()
            .build();

        CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenAcceptAsync(resp -> handleResponse(resp, current), EXECUTOR)
            .exceptionallyAsync(t -> {
                LOGGER.warn("Version check: HTTP request failed: {}", t.toString());
                VersionCheckState.accept(VersionCheckState.Status.ERROR, null);
                return null;
            }, EXECUTOR);
    }

    private static void handleResponse(HttpResponse<String> resp, String current) {
        if (resp.statusCode() != 200) {
            LOGGER.warn("Version check: GitHub returned HTTP {}", resp.statusCode());
            VersionCheckState.accept(VersionCheckState.Status.ERROR, null);
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            String tagName = json.has("tag_name") ? json.get("tag_name").getAsString() : null;
            if (tagName == null || tagName.isEmpty()) {
                LOGGER.warn("Version check: latest release JSON missing tag_name");
                VersionCheckState.accept(VersionCheckState.Status.ERROR, null);
                return;
            }
            String latest = SemverCompare.stripV(tagName);
            int cmp = SemverCompare.compare(current, latest);
            VersionCheckState.Status next;
            if (cmp == 0)      next = VersionCheckState.Status.LATEST;
            else if (cmp < 0)  next = VersionCheckState.Status.UPDATE_AVAILABLE;
            else               next = VersionCheckState.Status.AHEAD;
            VersionCheckState.accept(next, latest);
            LOGGER.info("Version check: current={} latest={} -> {}", current, latest, next);
        } catch (Exception e) {
            LOGGER.warn("Version check: failed to parse GitHub response", e);
            VersionCheckState.accept(VersionCheckState.Status.ERROR, null);
        }
    }

    static String currentModVersion() {
        return DtPlatform.get().getModVersion(DtCore.MOD_ID).orElse("unknown");
    }
}
