package games.brennan.dungeontrain.client.version.compare;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.VersionInfo;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One anonymous GET per platform for the modpack's version listing, parsed off-thread and pushed
 * into {@link VersionCompareState}. Same shape as
 * {@link games.brennan.dungeontrain.client.version.GitHubLatestReleaseFetcher}: a single daemon
 * thread owns the client, so overlapping triggers serialise and nothing holds JVM shutdown.
 *
 * <p>Modrinth's API is public and keyless. CurseForge's official API needs a key that cannot ship
 * in a client jar, so its listing comes from the keyless {@code api.cfwidget.com} mirror, which
 * lags the real listing by up to ~30 minutes and answers {@code 202} while it warms a project it
 * has not seen recently. Both are well inside CurseForge's own review lag, which is the thing the
 * page reports; a stale-by-minutes number is fine, a missing one is shown as unavailable.</p>
 *
 * <p>Modrinth caps a page at 200 versions. The pack publishes several times a day, so that is a
 * couple of months of history — a player further behind than that reads as "200+ versions
 * behind", which is true enough.</p>
 */
final class PackVersionFetcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String MODRINTH_PACK_PROJECT = "bEFyz3ji";
    static final String CURSEFORGE_PACK_PROJECT = "1556213";
    static final int MODRINTH_PAGE = 200;

    private static final URI MODRINTH_URL = URI.create(
            "https://api.modrinth.com/v2/project/" + MODRINTH_PACK_PROJECT + "/version?limit=" + MODRINTH_PAGE);
    private static final URI CURSEFORGE_URL = URI.create(
            "https://api.cfwidget.com/" + CURSEFORGE_PACK_PROJECT);

    private static final String MODRINTH_MOD_FILTER = "?loaders=%5B%22neoforge%22%5D&game_versions=%5B%221.21.1%22%5D";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DungeonTrain-PackVersions");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .executor(EXECUTOR)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private PackVersionFetcher() {}

    static void fetchAsync(Platform platform) {
        URI url = platform == Platform.MODRINTH ? MODRINTH_URL : CURSEFORGE_URL;
        Function<String, PlatformVersions> parser = platform == Platform.MODRINTH
                ? VersionCatalogParser::parseModrinth
                : VersionCatalogParser::parseCurseForge;
        fetch("Pack versions (" + platform + ")", url, parser,
                VersionCompareState::accept, () -> VersionCompareState.fail(platform));
    }

    /** One sibling's Modrinth listing, narrowed to this loader + Minecraft version. */
    static void fetchSiblingAsync(SiblingMod mod) {
        URI url = URI.create("https://api.modrinth.com/v2/project/" + mod.modrinthSlug() + "/version" + MODRINTH_MOD_FILTER);
        fetch("Sibling versions (" + mod.modId() + ")", url, VersionCatalogParser::parseModrinth,
                versions -> VersionCompareState.acceptSibling(mod, versions),
                () -> VersionCompareState.failSibling(mod));
    }

    private static void fetch(String what, URI url, Function<String, PlatformVersions> parser,
                              Consumer<PlatformVersions> onOk, Runnable onFail) {
        HttpRequest req = HttpRequest.newBuilder(url)
                .header("User-Agent", "DungeonTrain-Mod/" + VersionInfo.VERSION + " (github.com/bh679/dungeon-train-mc)")
                .header("Accept", "application/json")
                .timeout(TIMEOUT)
                .GET()
                .build();

        CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAcceptAsync(resp -> handle(what, resp, parser, onOk, onFail), EXECUTOR)
                .exceptionallyAsync(t -> {
                    LOGGER.warn("{}: request failed: {}", what, t.toString());
                    onFail.run();
                    return null;
                }, EXECUTOR);
    }

    private static void handle(String what, HttpResponse<String> resp, Function<String, PlatformVersions> parser,
                               Consumer<PlatformVersions> onOk, Runnable onFail) {
        if (resp.statusCode() != 200) {
            LOGGER.warn("{}: HTTP {}", what, resp.statusCode());
            onFail.run();
            return;
        }
        try {
            PlatformVersions versions = parser.apply(resp.body());
            LOGGER.info("{}: {} listed, latest {}", what, versions.entries().size(),
                    versions.latest().map(e -> e.version().toString()).orElse("none"));
            onOk.accept(versions);
        } catch (RuntimeException e) {
            LOGGER.warn("{}: could not parse listing", what, e);
            onFail.run();
        }
    }
}
