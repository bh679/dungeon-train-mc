package games.brennan.dungeontrain.net.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.moderation.LiveWordList;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Off-thread reader for {@code GET /live/moderation/words} — the relay's overlay for
 * {@link LiveWordList}. Same shape as {@link games.brennan.dungeontrain.cheat.CheatModListFetcher}:
 * own {@link HttpClient}, anonymous, once per session, fire-and-forget, fully no-throw. A failure
 * leaves the baked list and the last cached overlay in force.
 */
public final class LiveWordListFetcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static volatile boolean attempted;
    private static volatile boolean failed;

    private LiveWordListFetcher() {}

    /** Kick off the one-per-session fetch (retrying a previously failed attempt). No-throw. */
    public static void ensureFetched() {
        if (attempted && !failed) return;
        attempted = true;
        failed = false;
        fetchAsync();
    }

    static void fetchAsync() {
        try {
            String url = DungeonTrain.relayBaseUrl() + "/live/moderation/words";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] live word list fetch failed: {}", err.toString());
                                failed = true;
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] live word list fetch -> HTTP {}", resp.statusCode());
                                failed = true;
                                return;
                            }
                            LiveWordList.Payload p = LiveWordList.parse(resp.body());
                            if (p == LiveWordList.Payload.EMPTY) {
                                failed = true;
                                return;
                            }
                            LiveWordList.accept(p);
                            LOGGER.info("[DungeonTrain] live word list updated from relay (v{}, {} term(s))",
                                p.version(), LiveWordList.effective().termCount());
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] live word list parse failed: {}", t.toString());
                            failed = true;
                        }
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] live word list request failed to start: {}", t.toString());
            failed = true;
        }
    }
}
