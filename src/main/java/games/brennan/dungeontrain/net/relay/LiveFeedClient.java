package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.live.LiveClaimInfo;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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

    /** Where a chat line was typed: the relay's {@code source} field. */
    public enum Source {
        GAME, DISCORD, TWITCH, OTHER;

        /** {@code "game"} → GAME, {@code "discord"} → DISCORD, {@code "twitch"} → TWITCH; anything else OTHER. */
        public static Source parse(@Nullable String s) {
            if (s == null) return OTHER;
            return switch (s) {
                case "game" -> GAME;
                case "discord" -> DISCORD;
                case "twitch" -> TWITCH;
                default -> OTHER;
            };
        }
    }

    /**
     * One line of the stream's chat room. {@code uuid} is the Minecraft player who typed it when it
     * came from the game (dashes stripped, lower-case), null for a Discord or Twitch post; {@code source}
     * tells the rooms apart.
     */
    public record ChatLine(long seq, long ts, String author, @Nullable String uuid, String content, Source source) {
        /** Typed in some Minecraft world (and so possibly already shown by vanilla chat). */
        public boolean fromGame() {
            return source == Source.GAME;
        }
    }

    /** A chat poll: whether anyone is on air, the ring's head seq, and the lines after {@code since}. */
    public record ChatPage(boolean live, long seq, List<ChatLine> lines) {
        public static final ChatPage EMPTY = new ChatPage(false, -1, List.of());
    }

    /**
     * A smaller copy of the stream the relay's renditions worker has ready: {@code width} pixels
     * wide (16:9), at its own playlist URL. The source stream is not a variant.
     */
    public record Variant(int width, int height, String playlistUrl) {}

    /**
     * What a viewer sees. {@code variants} are the ready renditions, smallest first — empty from an
     * older relay or while the worker is off, stale or warming up.
     */
    public record Status(boolean live, @Nullable String session, @Nullable String playlistUrl,
                         @Nullable String streamer, int viewers, int cap, boolean slot,
                         @Nullable String replayUrl, boolean ended, List<Variant> variants) {
        public static final Status OFFLINE = new Status(false, null, null, null, 0, 0, true, null, false, List.of());

        /**
         * The playlist to decode at {@code decodeWidth}: the smallest variant at least that wide, else
         * the source. A variant narrower than the decode would be upscaled — never picked.
         */
        public @Nullable String mediaUrlFor(int decodeWidth) {
            for (Variant v : variants) {
                if (v.width() >= decodeWidth) return v.playlistUrl();
            }
            return playlistUrl;
        }
    }

    /**
     * {@code variants[]} from a status body: even positive widths with an {@code .m3u8} playlist on
     * https (or on plain http only when the source playlist itself is — a local relay with a
     * stand-in bucket), sorted by width.
     */
    static List<Variant> parseVariants(JsonObject body, String sourcePlaylistUrl) {
        if (body == null || !body.has("variants") || !body.get("variants").isJsonArray()) return List.of();
        boolean plainHttpOk = sourcePlaylistUrl != null && sourcePlaylistUrl.startsWith("http://");
        List<Variant> out = new ArrayList<>();
        for (JsonElement e : body.getAsJsonArray("variants")) {
            if (!e.isJsonObject()) continue;
            JsonObject v = e.getAsJsonObject();
            if (!v.has("width") || !v.has("playlistUrl") || !v.get("width").isJsonPrimitive() || !v.get("playlistUrl").isJsonPrimitive()) continue;
            int width;
            try { width = v.get("width").getAsInt(); } catch (RuntimeException ex) { continue; }
            String url = v.get("playlistUrl").getAsString();
            boolean scheme = url.startsWith("https://") || (plainHttpOk && url.startsWith("http://"));
            if (width <= 0 || width % 2 != 0 || !scheme || !url.endsWith(".m3u8")) continue;
            int height = v.has("height") && v.get("height").isJsonPrimitive() ? v.get("height").getAsInt() : Math.round(width * 9f / 16f);
            out.add(new Variant(width, height, url));
        }
        out.sort(Comparator.comparingInt(Variant::width));
        return List.copyOf(out);
    }

    private static String base() {
        return DungeonTrain.relayBaseUrl();
    }

    public static CompletableFuture<Result> claim(UUID uuid, String name, String eyeId,
                                                  LiveClaimInfo info) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid.toString());
        body.addProperty("name", name);
        // One id per Live Eye item: the relay's #live-eye post tells a relog from a new Live Eye by it.
        if (eyeId != null && !eyeId.isEmpty()) body.addProperty("eyeId", eyeId);
        // For the #live-eye post's streamer line: version + survey freshness dot, and Live Chat on/off.
        if (info != null) {
            if (!info.version().isEmpty()) body.addProperty("version", info.version());
            body.addProperty("versionDot", info.versionDot());
            body.addProperty("chat", info.chat());
        }
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

    /**
     * The stream's Discord chat after {@code since} (a seq; negative = just the head, no backlog), on
     * {@code base} — the streamer's own relay or the viewer channel's.
     */
    public static CompletableFuture<Result> chat(String base, long since) {
        String path = "/live/chat" + (since < 0 ? "" : "?since=" + since);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(RELAY_TIMEOUT).header("Accept", "application/json").GET().build();
        return send(RELAY, req);
    }

    /** Post one in-game line into the stream's Discord chat as this player. */
    public static CompletableFuture<Result> chatSend(String base, UUID uuid, String name, String content) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid.toString());
        body.addProperty("name", name);
        body.addProperty("content", content);
        return post(base, "/live/chat/send", body);
    }

    /** A chat poll's page; {@link ChatPage#EMPTY} on any failure (seq −1 keeps the cursor untouched). */
    public static ChatPage parseChat(Result r) {
        if (!r.ok()) return ChatPage.EMPTY;
        JsonObject b = r.body();
        try {
            boolean live = b.has("live") && b.get("live").getAsBoolean();
            long seq = b.has("seq") ? b.get("seq").getAsLong() : -1;
            List<ChatLine> lines = new ArrayList<>();
            if (b.has("messages") && b.get("messages").isJsonArray()) {
                for (JsonElement el : b.getAsJsonArray("messages")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject m = el.getAsJsonObject();
                    String content = m.has("content") && !m.get("content").isJsonNull() ? m.get("content").getAsString() : "";
                    if (content.isBlank() || !m.has("seq")) continue;
                    String author = m.has("author") && !m.get("author").isJsonNull() ? m.get("author").getAsString() : "";
                    String uuid = m.has("uuid") && !m.get("uuid").isJsonNull() ? m.get("uuid").getAsString() : null;
                    Source source = Source.parse(m.has("source") && !m.get("source").isJsonNull() ? m.get("source").getAsString() : null);
                    lines.add(new ChatLine(m.get("seq").getAsLong(), m.has("ts") ? m.get("ts").getAsLong() : 0L,
                        author.isBlank() ? "Someone" : author, uuid, content, source));
                }
            }
            return new ChatPage(live, seq, List.copyOf(lines));
        } catch (RuntimeException e) {
            return ChatPage.EMPTY;
        }
    }

    /** {@code reason} (nullable) says why — died, removed, left, … — for the #live-eye "has gone offline" post. */
    public static CompletableFuture<Result> stop(String token, @Nullable String reason) {
        JsonObject body = new JsonObject();
        body.addProperty("token", token);
        if (reason != null && !reason.isEmpty()) body.addProperty("reason", reason);
        return post("/live/stop", body);
    }

    /** Which Live Feed channel a viewer watches: the public one, or the dev one Free Play TVs show. */
    public enum Channel { LIVE, DEV }

    /**
     * Everyone watches the public feed, except a Free Play run while a dev is streaming: then its TVs
     * show the dev channel. On every build.
     */
    public static Channel viewerChannel(boolean freePlay, boolean devLive) {
        return freePlay && devLive ? Channel.DEV : Channel.LIVE;
    }

    /** The relay base a viewer of {@code channel} reads from. */
    public static String viewerBase(Channel channel) {
        return channel == Channel.DEV ? DungeonTrain.devRelayBaseUrl() : DungeonTrain.liveRelayBaseUrl();
    }

    public static CompletableFuture<Result> status(@Nullable UUID viewer, Channel channel) {
        return status(viewer, channel, false);
    }

    /**
     * {@link #status(UUID, Channel)}, and with {@code ranked} the viewer's watching may count toward
     * the relay's "Longest Watching" leaderboard. Pass it only with the player's consent.
     */
    public static CompletableFuture<Result> status(@Nullable UUID viewer, Channel channel, boolean ranked) {
        String path = statusPath(viewer, ranked);
        HttpRequest req = HttpRequest.newBuilder(URI.create(viewerBase(channel) + path))
            .timeout(RELAY_TIMEOUT).header("Accept", "application/json").GET().build();
        return send(RELAY, req);
    }

    /** The status request's path: {@code ?viewer=} names who is watching, {@code &lb=1} lets it rank. */
    static String statusPath(@Nullable UUID viewer, boolean ranked) {
        if (viewer == null) return "/live/status";
        return "/live/status?viewer=" + viewer + (ranked ? "&lb=1" : "");
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
        if (!live) return new Status(false, null, null, null, viewers, cap, slot, replayUrl, false, List.of());
        // Ended: the streamer stopped and the relay keeps the stream on air while viewers play out its
        // tail; replayUrl is where a TV goes once it has reached the end.
        boolean ended = b.has("ended") && b.get("ended").getAsBoolean();
        return new Status(true, r.str("session"), r.str("playlistUrl"), r.str("streamer"), viewers, cap, slot,
            ended ? replayUrl : null, ended, parseVariants(b, r.str("playlistUrl")));
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
        return post(base(), path, body);
    }

    private static CompletableFuture<Result> post(String base, String path, JsonObject body) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
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
