package games.brennan.dungeontrain.client.videos;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Off-thread poster for {@code POST /<CAP>/videos/submit} — a player-suggested link into the
 * operator's review queue. Anonymous: the body is the URL and nothing else. The relay answers with
 * what happened, mapped here to a {@link Result} the submit screen can put into words.
 */
public final class VideoSubmitter {

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Result {
        /** In the review queue; it shows up in the list once approved. */
        QUEUED,
        /** The relay already lists this video. */
        ALREADY_LISTED,
        /** Somebody (maybe this player) already suggested it; it is waiting for review. */
        ALREADY_PENDING,
        /** Not a link to a YouTube / Bilibili / Twitch / Instagram video. */
        BAD_URL,
        /** Too many submissions from this connection; try later. */
        RATE_LIMITED,
        /** Network or relay failure. */
        FAILED
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    /** The relay fetches the video's metadata before answering, so this is longer than a plain GET. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);

    private VideoSubmitter() {}

    /** Post {@code url}; {@code onDone} runs on the HTTP thread — marshal to the render thread yourself. */
    public static void submitAsync(String url, Consumer<Result> onDone) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("url", url);
            HttpRequest req = HttpRequest.newBuilder(URI.create(DungeonTrain.relayBaseUrl() + "/videos/submit"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        if (err != null) {
                            LOGGER.debug("[DungeonTrain] video submit failed: {}", err.toString());
                            onDone.accept(Result.FAILED);
                            return;
                        }
                        onDone.accept(interpret(resp.statusCode(), resp.body()));
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] video submit failed to start: {}", t.toString());
            onDone.accept(Result.FAILED);
        }
    }

    /** Status code + body → outcome. Package-private for the unit test. */
    static Result interpret(int status, String body) {
        if (status == 429) return Result.RATE_LIMITED;
        if (status == 400) {
            return "bad_url".equals(field(body, "error")) ? Result.BAD_URL : Result.FAILED;
        }
        if (status / 100 != 2) return Result.FAILED;
        String s = field(body, "status");
        if (s == null) return Result.FAILED;
        return switch (s) {
            case "queued" -> Result.QUEUED;
            case "already_listed" -> Result.ALREADY_LISTED;
            case "already_pending" -> Result.ALREADY_PENDING;
            default -> Result.FAILED;
        };
    }

    private static String field(String body, String key) {
        try {
            var el = JsonParser.parseString(body == null ? "" : body);
            if (!el.isJsonObject()) return null;
            var v = el.getAsJsonObject().get(key);
            return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
