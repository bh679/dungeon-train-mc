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
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Off-thread poster for {@code POST /<CAP>/videos/submit} — a player-suggested link into the
 * operator's review queue, or (dev builds: the dev cap is the operator's) straight onto the list.
 * Anonymous: the body is the URL, what kind of link it is, and for a streamer whether they are live
 * right now. The relay answers with what happened, mapped here to a {@link Result} the submit screen
 * can put into words.
 */
public final class VideoSubmitter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** What the link is: a video (any platform) or a Twitch streamer's own channel. */
    public enum Kind {
        VIDEO("video"), STREAMER("streamer");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        /** Lang-key suffix under {@code gui.dungeontrain.videos.submit.kind.} and the relay's value. */
        public String key() {
            return wire;
        }
    }

    public enum Result {
        /** In the review queue; it shows up in the list once approved. */
        QUEUED,
        /** On the list already — the dev cap publishes without review. */
        PUBLISHED,
        /** A streamer who did not say they are live: nothing recorded, come back when streaming. */
        NOT_LIVE,
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

    /**
     * Post {@code url} as a {@code kind}; {@code live} is the streamer's "I'm live now" tick (ignored
     * for videos). {@code onDone} runs on the HTTP thread — marshal to the render thread yourself.
     */
    public static void submitAsync(String url, Kind kind, boolean live, Consumer<Result> onDone) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("url", url);
            body.addProperty("kind", (kind == null ? Kind.VIDEO : kind).key());
            if (kind == Kind.STREAMER) body.addProperty("live", live);
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
            String e = field(body, "error");
            if ("bad_url".equals(e)) return Result.BAD_URL;
            if ("not_live".equals(e)) return Result.NOT_LIVE;
            return Result.FAILED;
        }
        if (status / 100 != 2) return Result.FAILED;
        String s = field(body, "status");
        if (s == null) return Result.FAILED;
        return switch (s) {
            case "queued" -> Result.QUEUED;
            case "published" -> Result.PUBLISHED;
            case "already_listed" -> Result.ALREADY_LISTED;
            case "already_pending" -> Result.ALREADY_PENDING;
            default -> Result.FAILED;
        };
    }

    /**
     * Is this a Twitch <em>channel</em> link — {@code twitch.tv/<login>} and nothing more — the shape
     * the relay accepts for a streamer? Mirrors its bare-channel rule (reserved first segments are site
     * routes, not logins) so the obvious mistakes — a VOD, a clip, another platform — are caught
     * before a round trip.
     */
    static boolean isTwitchChannelUrl(String v) {
        if (!VideoCatalogFetcher.isValidUrl(v)) return false;
        try {
            URI u = URI.create(v.trim());
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            if (!host.equals("twitch.tv") && !host.equals("m.twitch.tv")) return false;
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            String[] segs = Arrays.stream(path.split("/")).filter(x -> !x.isEmpty()).toArray(String[]::new);
            if (segs.length != 1) return false;
            String login = segs[0].toLowerCase(Locale.ROOT);
            return login.matches("[a-z0-9_]{3,25}") && !TWITCH_RESERVED.contains(login);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** twitch.tv first-path segments that are site routes, not channel logins — the relay's own list. */
    private static final Set<String> TWITCH_RESERVED = Set.of(
            "videos", "clip", "clips", "directory", "settings", "subscriptions", "following", "friends",
            "inventory", "wallet", "drops", "prime", "turbo", "downloads", "jobs", "p", "u", "team", "teams",
            "collections", "search", "login", "signup", "wp-login", "privacy", "about", "redeem", "store");

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
