package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Off-thread reader for the relay's {@code GET /approved-mods} endpoint — the updatable overlay for
 * {@link ApprovedModList}. Modelled on {@link CheatModListFetcher}: own {@link HttpClient},
 * fire-and-forget, fully no-throw. Any failure just leaves the baked ∪ last-cached list in place,
 * which is the safe direction — a relay outage must never invent unapproved mods.
 *
 * <p>The request is anonymous — no uuid, session, or query params — so it runs regardless of the
 * network-consent setting (same deliberate product decision as the cheat-mod and official-links
 * overlays). It is always on: this is an integrity feature, and an off-switch would only let a
 * cheater freeze the list at whatever their jar shipped with.</p>
 */
public final class ApprovedModListFetcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Pin HTTP/1.1 for the same reason CheatModListFetcher does: Java's default HTTP/2 client can't
    // h2c-upgrade over plaintext http://, which breaks local 127.0.0.1 relay testing.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static volatile boolean attempted;
    private static volatile boolean failed;

    private ApprovedModListFetcher() {}

    /** Kick off the one-per-session relay fetch (retrying a previously failed attempt). No-throw. */
    public static void ensureFetched() {
        if (attempted && !failed) return;
        attempted = true;
        failed = false;
        fetchAsync();
    }

    /** Fetch the approved-mod list off-thread; results land in {@link ApprovedModList}. No-throw. */
    static void fetchAsync() {
        try {
            String url = DungeonTrain.relayBaseUrl() + "/approved-mods";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] approved-mod list fetch failed: {}", err.toString());
                                failed = true;
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] approved-mod list fetch -> HTTP {}", resp.statusCode());
                                failed = true;
                                return;
                            }
                            ApprovedModList.Payload payload = ApprovedModList.parse(resp.body());
                            ApprovedModList.accept(payload);
                            LOGGER.info("[DungeonTrain] approved-mod list updated from relay "
                                    + "({} approval(s), {} revocation(s), enforce={})",
                                payload.approved().size(), payload.revoked().size(), payload.enforce());
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] approved-mod list parse failed: {}", t.toString());
                            failed = true;
                        }
                    });
        } catch (Throwable t) {
            // Building the request failed synchronously — swallow; baked ∪ cache stay in force.
            LOGGER.debug("[DungeonTrain] approved-mod list request failed to start: {}", t.toString());
            failed = true;
        }
    }
}
