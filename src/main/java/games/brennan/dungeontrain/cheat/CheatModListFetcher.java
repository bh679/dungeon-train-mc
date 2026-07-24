package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/**
 * Off-thread reader for the relay's {@code GET /cheat-mods} endpoint — the updatable overlay for
 * {@link CheatModList}. Modelled on
 * {@link games.brennan.dungeontrain.client.links.OfficialLinksFetcher}: own {@link HttpClient},
 * fire-and-forget, fully no-throw. Any failure just leaves the baked ∪ last-cached list in place.
 *
 * <p>The request is anonymous — no uuid, session, or query params — so it runs regardless of the
 * network-consent setting (same deliberate product decision as the official-links overlay). It is
 * always on: this is an integrity feature, and an off-switch would only let a cheater disable
 * detection.</p>
 */
public final class CheatModListFetcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Pin HTTP/1.1: the relay is a cleartext-capable Node server; Java's default HTTP/2 client
    // can't h2c-upgrade over plaintext http:// (breaks local 127.0.0.1 testing). Harmless in prod.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static volatile boolean attempted;
    private static volatile boolean failed;

    private CheatModListFetcher() {}

    /** Kick off the one-per-session relay fetch (retrying a previously failed attempt). No-throw. */
    public static void ensureFetched() {
        if (attempted && !failed) return;
        attempted = true;
        failed = false;
        fetchAsync();
    }

    /** Fetch the cheat-mod list off-thread; results land in {@link CheatModList}. No-throw. */
    static void fetchAsync() {
        try {
            String url = DungeonTrain.relayBaseUrl() + "/cheat-mods";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] cheat-mod list fetch failed: {}", err.toString());
                                failed = true;
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] cheat-mod list fetch -> HTTP {}", resp.statusCode());
                                failed = true;
                                return;
                            }
                            Set<String> ids = CheatModList.parse(resp.body());
                            CheatModList.accept(ids);
                            LOGGER.info("[DungeonTrain] cheat-mod list updated from relay ({} id(s))", ids.size());
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] cheat-mod list parse failed: {}", t.toString());
                            failed = true;
                        }
                    });
        } catch (Throwable t) {
            // Building the request failed synchronously — swallow; baked ∪ cache stay in force.
            LOGGER.debug("[DungeonTrain] cheat-mod list request failed to start: {}", t.toString());
            failed = true;
        }
    }
}
