package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The Live Feed's relay calls, and the direct-to-R2 upload the relay signs for.
 *
 * <p>Relay routes (all under the cap base URL): {@code POST /live/claim} takes the channel,
 * {@code POST /live/presign} returns signed PUT URLs for named files (and is the heartbeat),
 * {@code POST /live/stop} ends a stream, {@code GET /live/status} is what viewers poll. The R2
 * PUT goes straight to the storage endpoint with exactly the headers the relay signed — a changed
 * {@code Cache-Control} is a 403 from R2, by design.</p>
 *
 * <p>Every method returns a {@link Result}; nothing here throws across the async boundary.</p>
 */
public final class LiveFeedClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient RELAY = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1) // the relay is bare Node; avoid h2c
        .connectTimeout(Duration.ofSeconds(8))
        .build();
    private static final HttpClient STORAGE = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private static final Duration RELAY_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(30);

    private LiveFeedClient() {}

    /** Status + parsed JSON body (null when the body was not an object). 0 = transport failure. */
    public record Result(int status, @Nullable JsonObject body, @Nullable Throwable error) {
        public boolean ok() { return status >= 200 && status < 300 && body != null; }
        public boolean forbidden() { return status == 403; }
        public String str(String key) {
            return body != null && body.has(key) && !body.get(key).isJsonNull() ? body.get(key).getAsString() : "";
        }
    }

    /** One signed upload: URL plus the exact headers to send with it. */
    public record SignedPut(String url, Map<String, String> headers, long expiresAt) {}

    /** What a claim gives the streamer. */
    public record Claim(String session, String token, String playlistUrl, @Nullable String prevName) {}

    /** What a viewer sees. */
    public record Status(boolean live, @Nullable String session, @Nullable String playlistUrl,
                         @Nullable String streamer, int viewers, int cap, boolean slot,
                         @Nullable String replayUrl, boolean ended) {
        public static final Status OFFLINE = new Status(false, null, null, null, 0, 0, true, null, false);
    }

    private static String base() {
        return DungeonTrain.relayBaseUrl();
    }

    public static CompletableFuture<Result> claim(UUID uuid, String name) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid.toString());
        body.addProperty("name", name);
        return post("/live/claim", body);
    }

    @Nullable
    public static Claim parseClaim(Result r) {
        if (!r.ok()) return null;
        String session = r.str("session"), token = r.str("token"), url = r.str("playlistUrl");
        if (session.isEmpty() || token.isEmpty() || url.isEmpty()) return null;
        String prev = r.str("prevName");
        return new Claim(session, token, url, prev.isEmpty() ? null : prev);
    }

    public static CompletableFuture<Result> presign(String token, List<String> files) {
        JsonObject body = new JsonObject();
        body.addProperty("token", token);
        JsonArray arr = new JsonArray();
        files.forEach(arr::add);
        body.add("files", arr);
        return post("/live/presign", body);
    }

    /** file name → signed PUT, in request order; empty on a malformed reply. */
    public static Map<String, SignedPut> parsePresign(Result r) {
        Map<String, SignedPut> out = new LinkedHashMap<>();
        if (!r.ok() || !r.body().has("urls") || !r.body().get("urls").isJsonObject()) return out;
        for (Map.Entry<String, JsonElement> e : r.body().getAsJsonObject("urls").entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject o = e.getValue().getAsJsonObject();
            if (!o.has("url")) continue;
            Map<String, String> headers = new LinkedHashMap<>();
            if (o.has("headers") && o.get("headers").isJsonObject()) {
                for (Map.Entry<String, JsonElement> h : o.getAsJsonObject("headers").entrySet()) {
                    headers.put(h.getKey(), h.getValue().getAsString());
                }
            }
            long exp = o.has("expiresAt") ? o.get("expiresAt").getAsLong() : 0L;
            out.put(e.getKey(), new SignedPut(o.get("url").getAsString(), headers, exp));
        }
        return out;
    }

    /**
     * How many are watching, from a presign reply's {@code viewers.total} — players at TVs or wearing
     * one plus website viewers, never the streamer. −1 when the relay sent no count (an older relay).
     */
    public static int parseViewerCount(Result r) {
        if (!r.ok() || !r.body().has("viewers") || !r.body().get("viewers").isJsonObject()) return -1;
        JsonObject v = r.body().getAsJsonObject("viewers");
        try {
            return v.has("total") ? Math.max(0, v.get("total").getAsInt()) : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    public static CompletableFuture<Result> stop(String token) {
        JsonObject body = new JsonObject();
        body.addProperty("token", token);
        return post("/live/stop", body);
    }

    public static CompletableFuture<Result> status(@Nullable UUID viewer) {
        String path = "/live/status" + (viewer == null ? "" : "?viewer=" + viewer);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + path))
            .timeout(RELAY_TIMEOUT).header("Accept", "application/json").GET().build();
        return send(RELAY, req);
    }

    public static Status parseStatus(Result r) {
        if (!r.ok()) return Status.OFFLINE;
        JsonObject b = r.body();
        boolean live = b.has("live") && b.get("live").getAsBoolean();
        int viewers = b.has("viewers") ? b.get("viewers").getAsInt() : 0;
        int cap = b.has("cap") ? b.get("cap").getAsInt() : 0;
        boolean slot = !b.has("slot") || b.get("slot").getAsBoolean();
        String replay = r.str("replayUrl");
        String replayUrl = replay.isEmpty() ? null : replay;
        if (!live) return new Status(false, null, null, null, viewers, cap, slot, replayUrl, false);
        // Ended: the streamer stopped and the relay keeps the stream on air while viewers play out its
        // tail; replayUrl is where a TV goes once it has reached the end.
        boolean ended = b.has("ended") && b.get("ended").getAsBoolean();
        return new Status(true, r.str("session"), r.str("playlistUrl"), r.str("streamer"), viewers, cap, slot,
            ended ? replayUrl : null, ended);
    }

    /** PUT a local file to a signed URL with exactly the signed headers. */
    public static CompletableFuture<Result> upload(SignedPut target, Path file) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(target.url()))
                .timeout(UPLOAD_TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofFile(file));
            target.headers().forEach(b::header);
            return STORAGE.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> new Result(resp.statusCode(), null, null))
                .exceptionally(t -> new Result(0, null, t));
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(new Result(0, null, t));
        }
    }

    private static CompletableFuture<Result> post(String path, JsonObject body) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + path))
            .timeout(RELAY_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        return send(RELAY, req);
    }

    private static CompletableFuture<Result> send(HttpClient client, HttpRequest req) {
        try {
            return client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> new Result(resp.statusCode(), parse(resp.body()), null))
                .exceptionally(t -> {
                    LOGGER.debug("[DungeonTrain] live relay call failed: {}", t.toString());
                    return new Result(0, null, t);
                });
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(new Result(0, null, t));
        }
    }

    @Nullable
    private static JsonObject parse(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonElement el = JsonParser.parseString(body);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
