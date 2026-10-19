package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.live.LiveChatRules;
import games.brennan.dungeontrain.client.live.LiveClaimInfo;
import games.brennan.dungeontrain.client.live.life.LiveLifeStats;
import games.brennan.dungeontrain.discord.chat.ChatReaction;
import games.brennan.dungeontrain.discord.chat.ChatReactionCodec;
import games.brennan.dungeontrain.discord.chat.ChatReactionEvent;
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
    /** Immediate re-tries of an upload whose connection failed (see {@link #withTransportRetry}). */
    private static final int UPLOAD_RETRIES = 2;

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

    /**
     * The streamer's own linked Twitch channel, as the relay knows it at claim time (twitch-links.js):
     * the handle, and whether they opted in to the restream onto it and to having its title set.
     */
    public record Twitch(String login, String url, boolean restream, boolean setTitle) {}

    /** What a claim gives the streamer. {@code twitch} is null when they have not linked a channel. */
    public record Claim(String session, String token, String playlistUrl, @Nullable String prevName, @Nullable Twitch twitch) {
        public Claim(String session, String token, String playlistUrl, @Nullable String prevName) {
            this(session, token, playlistUrl, prevName, null);
        }
    }

    /** Where a chat line was typed: the relay's {@code source} field. */
    public enum Source {
        GAME, DISCORD, TWITCH, WEB, OTHER;

        /** {@code "game"} → GAME, {@code "discord"} → DISCORD, {@code "twitch"} → TWITCH, {@code "web"} → WEB; anything else OTHER. */
        public static Source parse(@Nullable String s) {
            if (s == null) return OTHER;
            return switch (s) {
                case "game" -> GAME;
                case "discord" -> DISCORD;
                case "twitch" -> TWITCH;
                case "web" -> WEB;
                default -> OTHER;
            };
        }
    }

    /** How a game line's player is in the room: the relay's {@code via} — the Live Eye (the streamer) or a TV. */
    public enum Via {
        EYE, TV;

        /** {@code "eye"} → EYE, {@code "tv"} → TV; anything else (or none, from an older relay) null. */
        @Nullable
        public static Via parse(@Nullable String s) {
            if (s == null) return null;
            return switch (s) {
                case "eye" -> EYE;
                case "tv" -> TV;
                default -> null;
            };
        }
    }

    /**
     * One line of the stream's chat room. {@code uuid} is the Minecraft player who typed it when it
     * came from the game (dashes stripped, lower-case), null for a Discord or Twitch post; {@code source}
     * tells the rooms apart, and {@code via} (game lines only, may be null) how its player is in the room.
     */
    public record ChatLine(long seq, long ts, String author, @Nullable String uuid, String content, Source source,
                           @Nullable LogRequest logRequest, @Nullable Via via, @Nullable LiveTakeoverClient.PollLine poll,
                           @Nullable Moment moment, @Nullable RestartNotice restart) {
        public ChatLine(long seq, long ts, String author, @Nullable String uuid, String content, Source source,
                        @Nullable LogRequest logRequest, @Nullable Via via, @Nullable LiveTakeoverClient.PollLine poll,
                        @Nullable Moment moment) {
            this(seq, ts, author, uuid, content, source, logRequest, via, poll, moment, null);
        }

        public ChatLine(long seq, long ts, String author, @Nullable String uuid, String content, Source source,
                        @Nullable LogRequest logRequest, @Nullable Via via, @Nullable LiveTakeoverClient.PollLine poll) {
            this(seq, ts, author, uuid, content, source, logRequest, via, poll, null);
        }

        public ChatLine(long seq, long ts, String author, @Nullable String uuid, String content, Source source) {
            this(seq, ts, author, uuid, content, source, null, null, null);
        }

        public ChatLine(long seq, long ts, String author, @Nullable String uuid, String content, Source source,
                        @Nullable LogRequest logRequest, @Nullable Via via) {
            this(seq, ts, author, uuid, content, source, logRequest, via, null);
        }

        public ChatLine(long seq, long ts, String author, @Nullable String uuid, String content, Source source,
                        @Nullable LogRequest logRequest) {
            this(seq, ts, author, uuid, content, source, logRequest, null);
        }

        /** Typed in some Minecraft world (and so possibly already shown by vanilla chat). */
        public boolean fromGame() {
            return source == Source.GAME;
        }
    }

    /** What a streamer's moment line is about: the relay's {@code moment.kind}. */
    public enum MomentKind {
        ADVANCEMENT("advancement"), PHOTO("photo"), BOOK("book");

        public final String wire;

        MomentKind(String wire) {
            this.wire = wire;
        }

        /** {@code "advancement"} / {@code "photo"} / {@code "book"}; anything else (a newer relay's kind) null. */
        @Nullable
        public static MomentKind parse(@Nullable String s) {
            if (s == null) return null;
            for (MomentKind k : values()) {
                if (k.wire.equals(s)) return k;
            }
            return null;
        }
    }

    /**
     * A streamer's moment the relay attached to a game line ({@code moment}): an advancement earned, a
     * photo taken or a book written. {@code title} is the advancement or book title (empty for a photo),
     * {@code excerpt} a book's first lines (empty otherwise). Both already cleaned.
     */
    public record Moment(MomentKind kind, String title, String excerpt) {}

    /** Longest moment title kept; the relay caps at the same length. */
    public static final int MAX_MOMENT_TITLE = 80;
    /** Longest book excerpt kept; the relay caps at the same length. */
    public static final int MAX_MOMENT_EXCERPT = 240;

    /** The moment on a chat-line object, or null when it carries none (or one this client doesn't know). */
    @Nullable
    /**
     * The relay is restarting, or just has (live-restart.js): {@code resumeBy} the relay's estimate of
     * when chat and stream are back (relay clock, 0 when absent), {@code countdown} 3, 2, 1 (0 when
     * absent), {@code resumed} the line saying they are back.
     */
    public record RestartNotice(long resumeBy, int countdown, boolean resumed) {}

    @Nullable
    static RestartNotice parseRestart(JsonObject m) {
        if (!m.has("restart") || !m.get("restart").isJsonObject()) return null;
        JsonObject o = m.getAsJsonObject("restart");
        long resumeBy = num(o, "resumeBy");
        long countdown = num(o, "countdown");
        boolean resumed = o.has("resumed") && o.get("resumed").isJsonPrimitive()
            && o.get("resumed").getAsJsonPrimitive().isBoolean() && o.get("resumed").getAsBoolean();
        int count = countdown > 0 && countdown < 100 ? (int) countdown : 0;
        if (resumeBy <= 0 && count == 0 && !resumed) return null;
        return new RestartNotice(Math.max(0L, resumeBy), count, resumed);
    }

    private static long num(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive() || !o.get(key).getAsJsonPrimitive().isNumber()) return 0L;
        return o.get(key).getAsLong();
    }

    static Moment parseMoment(JsonObject m) {
        if (!m.has("moment") || !m.get("moment").isJsonObject()) return null;
        JsonObject o = m.getAsJsonObject("moment");
        MomentKind kind = MomentKind.parse(str(o, "kind"));
        if (kind == null) return null;
        return new Moment(kind, LiveChatRules.clean(str(o, "title"), MAX_MOMENT_TITLE),
            LiveChatRules.clean(str(o, "excerpt"), MAX_MOMENT_EXCERPT));
    }

    /**
     * A Developer log request the relay attached to a chat line ({@code cmd: "log_request"}). The relay
     * only issues one for a verified Developer account. {@code target} is the player name asked, or
     * empty for "whoever is streaming"; {@code id} dedupes repeats across polls.
     */
    public record LogRequest(String target, String id) {
        /** Whether this request is for the player {@code myName}, who is {@code streaming} or not. */
        public boolean isFor(String myName, boolean streaming) {
            if (target.isBlank()) return streaming;
            return target.equalsIgnoreCase(myName);
        }
    }

    /** The log request on a chat-line object, or null when it carries none. Package-private for tests. */
    @Nullable
    static LogRequest parseLogRequest(JsonObject m) {
        if (!m.has("cmd") || m.get("cmd").isJsonNull() || !"log_request".equals(m.get("cmd").getAsString())) return null;
        String id = m.has("reqId") && !m.get("reqId").isJsonNull() ? m.get("reqId").getAsString() : "";
        if (id.isBlank()) return null;
        String target = m.has("target") && !m.get("target").isJsonNull() ? m.get("target").getAsString().trim() : "";
        return new LogRequest(target, id);
    }

    /**
     * A chat poll: whether anyone is on air, the ring's head seq, the lines after {@code since}, and the
     * seqs of earlier lines removed since then ({@code deleted} — a Twitch mod deleted them).
     */
    public record ChatPage(boolean live, long seq, List<ChatLine> lines, List<Long> deleted, List<LineReactions> reactions) {
        public static final ChatPage EMPTY = new ChatPage(false, -1, List.of(), List.of(), List.of());

        public ChatPage(boolean live, long seq, List<ChatLine> lines) {
            this(live, seq, lines, List.of(), List.of());
        }

        public ChatPage(boolean live, long seq, List<ChatLine> lines, List<Long> deleted) {
            this(live, seq, lines, deleted, List.of());
        }
    }

    /** The current Discord reactions on an earlier chat line ({@code line} = its seq), and what changed. */
    public record LineReactions(long line, List<ChatReaction> list, @Nullable ChatReactionEvent event) {}

    /** The {@code reactions} array of a chat page; malformed entries are skipped. Package-private for tests. */
    static List<LineReactions> parseReactions(JsonObject b) {
        if (!b.has("reactions") || !b.get("reactions").isJsonArray()) return List.of();
        List<LineReactions> out = new ArrayList<>();
        for (JsonElement el : b.getAsJsonArray("reactions")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("line") || !o.get("line").isJsonPrimitive()) continue;
            List<ChatReaction> list = new ArrayList<>();
            if (o.has("list") && o.get("list").isJsonArray()) {
                for (JsonElement re : o.getAsJsonArray("list")) {
                    if (!re.isJsonObject() || list.size() >= ChatReactionCodec.MAX_EMOJI) continue;
                    JsonObject r = re.getAsJsonObject();
                    String name = str(r, "name");
                    if (name.isBlank()) continue;
                    List<String> users = new ArrayList<>();
                    if (r.has("users") && r.get("users").isJsonArray()) {
                        for (JsonElement u : r.getAsJsonArray("users")) {
                            if (u.isJsonPrimitive()) users.add(LiveChatRules.clean(u.getAsString(), 32));
                        }
                    }
                    int count = r.has("count") && r.get("count").isJsonPrimitive() ? r.get("count").getAsInt() : users.size();
                    boolean animated = r.has("animated") && r.get("animated").isJsonPrimitive() && r.get("animated").getAsBoolean();
                    list.add(new ChatReaction(name, str(r, "id"), animated, Math.max(count, users.size()), users));
                }
            }
            ChatReactionEvent event = null;
            if (o.has("event") && o.get("event").isJsonObject()) {
                JsonObject e = o.getAsJsonObject("event");
                event = new ChatReactionEvent(str(e, "action"), str(e, "name"), str(e, "id"),
                    LiveChatRules.clean(str(e, "userName"), 32));
            }
            out.add(new LineReactions(o.get("line").getAsLong(), List.copyOf(list), event));
        }
        return List.copyOf(out);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
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
                         @Nullable String replayUrl, boolean ended, List<Variant> variants, @Nullable String twitchLogin) {
        public static final Status OFFLINE = new Status(false, null, null, null, 0, 0, true, null, false, List.of(), null);

        public Status(boolean live, @Nullable String session, @Nullable String playlistUrl, @Nullable String streamer, int viewers,
                      int cap, boolean slot, @Nullable String replayUrl, boolean ended, List<Variant> variants) {
            this(live, session, playlistUrl, streamer, viewers, cap, slot, replayUrl, ended, variants, null);
        }

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

    /** A plain claim: no takeover choice or ticket yet. */
    public static CompletableFuture<Result> claim(UUID uuid, String name, String eyeId,
                                                  LiveClaimInfo info, @Nullable Life life) {
        return claim(uuid, name, eyeId, info, life, false, null, null);
    }

    /**
     * Take the channel. This mod always asks to be told who holds it first ({@code confirmFlow}: a 409
     * the claim reads with {@link LiveTakeoverClient#parseBlocked}); {@code takeover} is the player's
     * "Take over" past the holder's protected minutes, {@code ticket} an approved takeover vote's pass,
     * {@code takeoverTicket} a won dead streamer's takeover ({@link #acceptTakeover}). Either ticket carries
     * the claim past the relay's tenure check, once.
     */
    public static CompletableFuture<Result> claim(UUID uuid, String name, String eyeId,
                                                  LiveClaimInfo info, @Nullable Life life,
                                                  boolean takeover, @Nullable String ticket,
                                                  @Nullable String takeoverTicket) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid.toString());
        body.addProperty("name", name);
        body.addProperty("confirmFlow", true);
        if (takeover) body.addProperty("takeover", true);
        if (ticket != null && !ticket.isEmpty()) body.addProperty("ticket", ticket);
        if (takeoverTicket != null && !takeoverTicket.isEmpty()) body.addProperty("takeoverTicket", takeoverTicket);
        // Which life this stream is in, for the one-life "Longest Stream" board. A death ends the
        // stream, so a session never spans two lives.
        if (life != null) {
            body.addProperty("lifeId", life.lifeId());
            body.addProperty("freePlay", life.freePlay());
        }
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
        return new Claim(session, token, url, prev.isEmpty() ? null : prev, parseTwitch(r.body()));
    }

    /** The {@code twitch} object of a claim or status, or null when absent/malformed. The login is checked against Twitch's own alphabet. */
    @Nullable
    static Twitch parseTwitch(@Nullable JsonObject body) {
        if (body == null || !body.has("twitch") || !body.get("twitch").isJsonObject()) return null;
        JsonObject t = body.getAsJsonObject("twitch");
        String login = t.has("login") && t.get("login").isJsonPrimitive() ? t.get("login").getAsString().toLowerCase() : "";
        if (!login.matches("[a-z0-9_]{3,25}")) return null;
        return new Twitch(login, "https://twitch.tv/" + login,
            t.has("restream") && t.get("restream").isJsonPrimitive() && t.get("restream").getAsBoolean(),
            t.has("setTitle") && t.get("setTitle").isJsonPrimitive() && t.get("setTitle").getAsBoolean());
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
            // Only an https PUT target (or loopback http, a local relay's stand-in bucket).
            if (!LiveUrls.allowed(o.get("url").getAsString(), "")) continue;
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
     * The longest watchers of the streamer's life so far, from a presign or stop reply's
     * {@code crowd.top} (dp-relay live-crowd.js), longest first, up to {@link #CROWD_MAX}. Empty against an older relay or a
     * malformed reply; malformed entries are skipped.
     */
    /** How many watchers the relay sends; the mod ranks its own podium from them plus the chat. */
    static final int CROWD_MAX = 10;

    public static List<LiveLifeStats.Watcher> parseCrowd(Result r) {
        if (!r.ok() || !r.body().has("crowd") || !r.body().get("crowd").isJsonObject()) return List.of();
        JsonObject crowd = r.body().getAsJsonObject("crowd");
        if (!crowd.has("top") || !crowd.get("top").isJsonArray()) return List.of();
        List<LiveLifeStats.Watcher> out = new ArrayList<>();
        for (JsonElement e : crowd.getAsJsonArray("top")) {
            if (!e.isJsonObject() || out.size() >= CROWD_MAX) continue;
            JsonObject o = e.getAsJsonObject();
            try {
                long seconds = o.has("seconds") ? o.get("seconds").getAsLong() : 0;
                String source = o.has("source") && o.get("source").isJsonPrimitive() ? o.get("source").getAsString() : "";
                if (seconds <= 0 || !("game".equals(source) || "web".equals(source))) continue;
                String name = o.has("name") && o.get("name").isJsonPrimitive() ? o.get("name").getAsString() : null;
                String uuid = o.has("uuid") && o.get("uuid").isJsonPrimitive() ? o.get("uuid").getAsString() : null;
                if (uuid != null && !uuid.matches("[0-9a-fA-F]{32}")) uuid = null;
                out.add(new LiveLifeStats.Watcher(name == null || name.isBlank() ? null : name, uuid, source, seconds));
            } catch (RuntimeException ignored) {
                // a bad entry is skipped, the rest still show
            }
        }
        return List.copyOf(out);
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

    /**
     * Share one of the streamer's moments into the stream's chat. {@code token} (the stream's upload
     * token) proves the poster is on air; the relay names them from its claim. {@code png} may be null
     * (no image, or not a photo).
     */
    public static CompletableFuture<Result> chatMoment(String base, String token, MomentKind kind, String title,
                                                       String excerpt, @Nullable byte[] png) {
        JsonObject body = new JsonObject();
        body.addProperty("token", token);
        body.addProperty("kind", kind.wire);
        if (!title.isEmpty()) body.addProperty("title", title);
        if (!excerpt.isEmpty()) body.addProperty("excerpt", excerpt);
        if (png != null && png.length > 0) body.addProperty("image", java.util.Base64.getEncoder().encodeToString(png));
        return post(base, "/live/chat/moment", body);
    }

    /**
     * A TV viewer who has never streamed joined the chat (the relay's {@code joins[]}, streamer only):
     * {@code joinId} is what the streamer gifts. {@code uuid} is dashes-stripped, lower-case.
     */
    public record GiftJoin(long seq, String joinId, String uuid, String name) {}

    /** The streamer {@code from} offered the viewer {@code target} a Live Eye (the relay's {@code gifts[]}). */
    public record GiftOffer(long seq, String giftId, String target, String from) {}

    /** A chat poll's gift notices; both empty on any failure or from an older relay. */
    public record GiftNotices(List<GiftJoin> joins, List<GiftOffer> gifts) {
        public static final GiftNotices EMPTY = new GiftNotices(List.of(), List.of());
    }

    /** A joinId / giftId as the relay mints them; anything else is dropped before it reaches a command. */
    private static final java.util.regex.Pattern GIFT_ID = java.util.regex.Pattern.compile("[jg]-[A-Za-z0-9_-]{8,24}");

    /** Whether {@code id} has the shape of a relay join or gift id. */
    public static boolean isGiftId(@Nullable String id) {
        return id != null && GIFT_ID.matcher(id).matches();
    }

    /** The {@code joins} / {@code gifts} arrays of a chat poll; malformed entries are skipped. */
    public static GiftNotices parseGiftNotices(Result r) {
        if (!r.ok()) return GiftNotices.EMPTY;
        JsonObject b = r.body();
        try {
            List<GiftJoin> joins = new ArrayList<>();
            if (b.has("joins") && b.get("joins").isJsonArray()) {
                for (JsonElement el : b.getAsJsonArray("joins")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    String id = str(o, "joinId");
                    if (!isGiftId(id) || !o.has("seq")) continue;
                    String name = LiveChatRules.clean(str(o, "name"), LiveChatRules.MAX_AUTHOR);
                    joins.add(new GiftJoin(o.get("seq").getAsLong(), id, str(o, "uuid"), name.isBlank() ? "Someone" : name));
                }
            }
            List<GiftOffer> gifts = new ArrayList<>();
            if (b.has("gifts") && b.get("gifts").isJsonArray()) {
                for (JsonElement el : b.getAsJsonArray("gifts")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    String id = str(o, "giftId");
                    String target = str(o, "target");
                    if (!isGiftId(id) || target.isBlank() || !o.has("seq")) continue;
                    String from = LiveChatRules.clean(str(o, "from"), LiveChatRules.MAX_AUTHOR);
                    gifts.add(new GiftOffer(o.get("seq").getAsLong(), id, target, from.isBlank() ? "Someone" : from));
                }
            }
            return new GiftNotices(List.copyOf(joins), List.copyOf(gifts));
        } catch (RuntimeException e) {
            return GiftNotices.EMPTY;
        }
    }

    /**
     * This client joined the stream's chat through a TV. {@code eligible}: not Kid mode and Livestreaming
     * on — only then may the streamer be offered to gift it a Live Eye.
     */
    public static CompletableFuture<Result> chatJoin(String base, UUID uuid, String name, boolean eligible) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid.toString());
        body.addProperty("name", name);
        body.addProperty("eligible", eligible);
        return post(base, "/live/chat/join", body);
    }

    /** The streamer (proved by its stream {@code token}) offers the viewer of {@code joinId} a Live Eye. */
    public static CompletableFuture<Result> gift(String base, String token, String joinId) {
        JsonObject body = new JsonObject();
        body.addProperty("token", token);
        body.addProperty("joinId", joinId);
        return post(base, "/live/gift", body);
    }

    /** The viewer's server redeems {@code giftId} for {@code uuid}: 200 once, only for the gift's viewer. */
    public static CompletableFuture<Result> giftClaim(String base, String giftId, UUID uuid) {
        JsonObject body = new JsonObject();
        body.addProperty("giftId", giftId);
        body.addProperty("uuid", uuid.toString());
        return post(base, "/live/gift/claim", body);
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
                    // Cleaned here, not trusted from the relay: no § codes, control chars or line breaks, capped.
                    String content = LiveChatRules.clean(m.has("content") && !m.get("content").isJsonNull() ? m.get("content").getAsString() : null,
                        LiveChatRules.MAX_CONTENT);
                    if (content.isBlank() || !m.has("seq")) continue;
                    String author = LiveChatRules.clean(m.has("author") && !m.get("author").isJsonNull() ? m.get("author").getAsString() : null,
                        LiveChatRules.MAX_AUTHOR);
                    String uuid = m.has("uuid") && !m.get("uuid").isJsonNull() ? m.get("uuid").getAsString() : null;
                    Source source = Source.parse(m.has("source") && !m.get("source").isJsonNull() ? m.get("source").getAsString() : null);
                    Via via = Via.parse(m.has("via") && !m.get("via").isJsonNull() ? m.get("via").getAsString() : null);
                    lines.add(new ChatLine(m.get("seq").getAsLong(), m.has("ts") ? m.get("ts").getAsLong() : 0L,
                        author.isBlank() ? "Someone" : author, uuid, content, source, parseLogRequest(m), via,
                        LiveTakeoverClient.parsePollLine(m), parseMoment(m), parseRestart(m)));
                }
            }
            List<Long> deleted = new ArrayList<>();
            if (b.has("deleted") && b.get("deleted").isJsonArray()) {
                for (JsonElement el : b.getAsJsonArray("deleted")) {
                    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) deleted.add(el.getAsLong());
                }
            }
            return new ChatPage(live, seq, List.copyOf(lines), List.copyOf(deleted), parseReactions(b));
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

    // ---- death takeover --------------------------------------------------------------------------

    /**
     * The streamer died this moment: from now their stream protects nothing, so anyone putting a Live
     * Eye on takes the channel at once. The offer to viewers ({@link #died}) still waits for them to
     * have seen the death. An older relay answers 404, which changes nothing.
     */
    public static CompletableFuture<Result> markDied(String token) {
        JsonObject body = new JsonObject();
        body.addProperty("token", token);
        return post("/live/died/mark", body);
    }

    /**
     * The streamer died (they may stream on through the death screens): the relay offers the stream
     * to every in-game viewer, and the first to accept takes it over.
     */
    public static CompletableFuture<Result> died(String token) {
        JsonObject body = new JsonObject();
        body.addProperty("token", token);
        return post("/live/died", body);
    }

    /** A dead streamer's stream on offer: {@code id} to accept it, {@code from} who died, open {@code untilMs} more. */
    public record Takeover(String id, String from, long untilMs) {}

    /** The {@code takeover} offer in a status body, or null (none, or from an older relay). */
    @Nullable
    public static Takeover parseTakeover(Result r) {
        if (!r.ok() || !r.body().has("takeover") || !r.body().get("takeover").isJsonObject()) return null;
        JsonObject t = r.body().getAsJsonObject("takeover");
        String id = str(t, "id");
        if (!id.matches("[0-9a-f]{8,64}")) return null;
        long until;
        try {
            until = t.has("untilMs") ? t.get("untilMs").getAsLong() : 0L;
        } catch (RuntimeException e) {
            return null;
        }
        if (until <= 0) return null;
        return new Takeover(id, LiveChatRules.clean(str(t, "from"), 32), until);
    }

    /** First to accept wins: 200 {@code {ticket}}; 409 {@code taken} / {@code gone} for everyone after. */
    public static CompletableFuture<Result> acceptTakeover(Channel channel, String id, UUID uuid, String name) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        body.addProperty("uuid", uuid.toString());
        body.addProperty("name", name);
        return post(viewerBase(channel), "/live/takeover/accept", body);
    }

    /** The winner's game server, before it swaps their TV for a Live Eye: is {@code ticket} theirs and unspent? */
    public static CompletableFuture<Boolean> verifyTakeover(String ticket, UUID uuid) {
        if (!ticket.matches("[0-9a-f]{16,128}")) return CompletableFuture.completedFuture(false);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/live/takeover/verify?ticket=" + ticket + "&uuid=" + uuid))
            .timeout(RELAY_TIMEOUT).header("Accept", "application/json").GET().build();
        return send(RELAY, req).handle((r, t) -> r != null && r.ok()
            && r.body().has("valid") && r.body().get("valid").getAsBoolean());
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
        return status(viewer, channel, null);
    }

    /**
     * The life a stream or a watch happened in, for the relay's one-life Live Feed boards. A blank
     * {@code lifeId} (a server that never said) earns the all-time boards only.
     */
    public record Life(String lifeId, boolean freePlay, String version) {
        public Life {
            lifeId = lifeId == null ? "" : lifeId;
            version = version == null ? "" : version;
        }
    }

    /**
     * {@link #status(UUID, Channel)}, and with a non-null {@code ranked} the viewer's watching counts
     * toward the relay's "Longest Viewer" boards — all-time, and one-life for {@code ranked}'s life.
     * Pass it only with the player's consent.
     */
    public static CompletableFuture<Result> status(@Nullable UUID viewer, Channel channel, @Nullable Life ranked) {
        return status(viewer, channel, ranked, null);
    }

    /**
     * As above; a ranked poll also carries {@code name}, the viewer's own player name, which shows on
     * the streamer's "the crowd" death page among this life's longest watchers.
     */
    public static CompletableFuture<Result> status(@Nullable UUID viewer, Channel channel, @Nullable Life ranked,
                                                   @Nullable String name) {
        String path = statusPath(viewer, ranked, name);
        HttpRequest req = HttpRequest.newBuilder(URI.create(viewerBase(channel) + path))
            .timeout(RELAY_TIMEOUT).header("Accept", "application/json").GET().build();
        return send(RELAY, req);
    }

    /**
     * The status request's path: {@code ?viewer=} names who is watching, {@code &lb=1} lets it rank,
     * and {@code life}/{@code fp}/{@code v} say which life it ranks in.
     */
    static String statusPath(@Nullable UUID viewer, @Nullable Life ranked) {
        return statusPath(viewer, ranked, null);
    }

    /** {@link #statusPath(UUID, Life)}, plus {@code &name=} on a ranked poll (consent already given). */
    static String statusPath(@Nullable UUID viewer, @Nullable Life ranked, @Nullable String name) {
        String path = statusPathBase(viewer, ranked);
        if (viewer == null || ranked == null || name == null || name.isBlank()) return path;
        return path + "&name=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String statusPathBase(@Nullable UUID viewer, @Nullable Life ranked) {
        if (viewer == null) return "/live/status";
        String path = "/live/status?viewer=" + viewer;
        if (ranked == null) return path;
        path += "&lb=1";
        if (ranked.lifeId().isEmpty()) return path;
        return path + "&life=" + ranked.lifeId() + "&fp=" + (ranked.freePlay() ? 1 : 0)
            + "&v=" + java.net.URLEncoder.encode(ranked.version(), java.nio.charset.StandardCharsets.UTF_8);
    }

    public static Status parseStatus(Result r) {
        if (!r.ok()) return Status.OFFLINE;
        JsonObject b = r.body();
        boolean live = b.has("live") && b.get("live").getAsBoolean();
        int viewers = b.has("viewers") ? b.get("viewers").getAsInt() : 0;
        int cap = b.has("cap") ? b.get("cap").getAsInt() : 0;
        boolean slot = !b.has("slot") || b.get("slot").getAsBoolean();
        // Relay-supplied URLs a viewer's ffmpeg will fetch: https (or loopback http) to an HLS playlist
        // or a .ts segment, or nothing. A live status with no usable playlist reads as off air.
        String replayUrl = LiveUrls.orNull(r.str("replayUrl"), ".ts");
        String playlistUrl = LiveUrls.orNull(r.str("playlistUrl"), ".m3u8");
        if (live && playlistUrl == null) {
            LOGGER.debug("[DungeonTrain] live status: refusing playlistUrl {}", r.str("playlistUrl"));
            live = false;
        }
        if (!live) return new Status(false, null, null, null, viewers, cap, slot, replayUrl, false, List.of());
        // Ended: the streamer stopped and the relay keeps the stream on air while viewers play out its
        // tail; replayUrl is where a TV goes once it has reached the end.
        boolean ended = b.has("ended") && b.get("ended").getAsBoolean();
        Twitch tw = parseTwitch(b);
        return new Status(true, r.str("session"), playlistUrl, r.str("streamer"), viewers, cap, slot,
            ended ? replayUrl : null, ended, parseVariants(b, playlistUrl), tw == null ? null : tw.login());
    }

    /** PUT a local file to a signed URL with exactly the signed headers. */
    public static CompletableFuture<Result> upload(SignedPut target, Path file) {
        return withTransportRetry(client -> put(client, target, () -> HttpRequest.BodyPublishers.ofFile(file)), UPLOAD_RETRIES);
    }

    /** PUT {@code body} (UTF-8) to a signed URL with exactly the signed headers — a rewritten playlist. */
    public static CompletableFuture<Result> upload(SignedPut target, String body) {
        return withTransportRetry(client -> put(client, target, () -> HttpRequest.BodyPublishers.ofString(body)), UPLOAD_RETRIES);
    }

    /** A request body that may fail to open (a file the encoder already deleted). */
    private interface Body {
        HttpRequest.BodyPublisher open() throws Exception;
    }

    private static CompletableFuture<Result> put(HttpClient client, SignedPut target, Body body) {
        // Only an https PUT target (or loopback http, a local relay's stand-in bucket).
        if (!LiveUrls.allowed(target.url(), "")) {
            return CompletableFuture.completedFuture(new Result(0, null,
                new IllegalArgumentException("refusing a non-https upload target")));
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(target.url()))
                .timeout(UPLOAD_TIMEOUT)
                .PUT(body.open());
            target.headers().forEach(b::header);
            return client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> new Result(resp.statusCode(), null, null))
                .exceptionally(t -> new Result(0, null, t));
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(new Result(0, null, t));
        }
    }

    /**
     * Run {@code attempt} on the shared storage client; on a transport failure (a pooled keep-alive
     * socket the far end already closed — "header parser received no bytes" — or a timeout) run it
     * again straight away, up to {@code retries} more times, each on a fresh client so it cannot pick
     * up another stale socket. A signed PUT is idempotent. An HTTP status (4xx/5xx) is never retried:
     * a bad payload stays bad.
     *
     * <p>Not {@code jdk.httpclient.keepalive.timeout}: the JDK reads it once per JVM, when the first
     * HttpClient anywhere is built, and it would change every client.</p>
     */
    static CompletableFuture<Result> withTransportRetry(java.util.function.Function<HttpClient, CompletableFuture<Result>> attempt, int retries) {
        return withTransportRetry(attempt, retries, STORAGE, LiveFeedClient::freshStorageClient);
    }

    /** As above, with the clients supplied (tests pass null clients and a counting attempt). */
    static CompletableFuture<Result> withTransportRetry(java.util.function.Function<HttpClient, CompletableFuture<Result>> attempt, int retries,
                                                       HttpClient first, java.util.function.Supplier<HttpClient> fresh) {
        return attempt.apply(first).thenCompose(r -> {
            if (retries <= 0 || !isTransportFailure(r)) return CompletableFuture.completedFuture(r);
            LOGGER.debug("[DungeonTrain] live upload transport failure, retrying on a fresh connection: {}", String.valueOf(r.error()));
            HttpClient client = fresh.get();
            return withTransportRetry(attempt, retries - 1, client, fresh)
                .whenComplete((ok, t) -> { if (client != null) client.shutdown(); });
        });
    }

    /** Status 0 from an I/O error or a timeout — the network, not the server, said no. */
    static boolean isTransportFailure(Result r) {
        if (r.status() != 0 || r.error() == null) return false;
        Throwable t = r.error();
        while ((t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        // HttpTimeoutException is an IOException; a missing local file is too, but retrying cannot help it.
        return t instanceof java.io.IOException
            && !(t instanceof java.io.FileNotFoundException || t instanceof java.nio.file.NoSuchFileException);
    }

    private static HttpClient freshStorageClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    static CompletableFuture<Result> post(String path, JsonObject body) {
        return post(base(), path, body);
    }

    /** GET {@code path} on the streamer's relay. */
    static CompletableFuture<Result> get(String path) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + path))
            .timeout(RELAY_TIMEOUT).header("Accept", "application/json").GET().build();
        return send(RELAY, req);
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
