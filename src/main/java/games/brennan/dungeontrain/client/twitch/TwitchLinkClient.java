package games.brennan.dungeontrain.client.twitch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.chat.RelayChatClient;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The relay half of tying this player's Twitch channel to their Minecraft uuid, so that while they wear
 * the Live Eye the people watching can find their channel — and, if they opt in, the stream is pushed
 * to that channel too ({@code /twitch}, {@link TwitchLinkCommand}; Options → Dungeon Train → Account).
 *
 * <p>Four calls, mirroring {@code CommunityLinkClient}. {@link #start} asks {@code POST /twitch/link/start}
 * for a short code and the web page that spends it after "Connect Twitch"; {@link #status} asks
 * {@code GET /twitch/link/status?uuid=} whether that has happened, which channel, and the two opt-ins;
 * {@link #setSettings} is {@code POST /twitch/link/settings}; {@link #unlink} is
 * {@code POST /twitch/link/unlink}. The relay holds the Twitch grant — no token, id or stream key ever
 * reaches the mod; the mod only ever sees the login and display name.</p>
 *
 * <p>Consent-gated like every credits call ({@link RelayChatClient#canConnect()}); every call resolves
 * off-thread and never throws across the boundary. The parsing is pure so it can be tested without a relay.</p>
 */
public final class TwitchLinkClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    static final String START_PATH = "/twitch/link/start";
    static final String STATUS_PATH = "/twitch/link/status";
    static final String SETTINGS_PATH = "/twitch/link/settings";
    static final String UNLINK_PATH = "/twitch/link/unlink";
    static final String CLAIM_PATH = "/twitch/link/claim";
    /** An older relay (or one without the feature): 404/405/501 on the path. */
    private static final Set<Integer> ENDPOINT_MISSING = Set.of(404, 405, 501);

    /** Why a call did not do what was asked. {@code NONE} on success. */
    public enum Error { NONE, NO_CONSENT, RATE_LIMITED, UNSUPPORTED, DISABLED, NOT_LINKED, NOT_VERIFIED, UNKNOWN_CHANNEL, ALREADY_VERIFIED, BAD_LOGIN, FAILED }

    /** A minted code, the page that takes it, and how long it lives; {@code ok} false with an {@link Error} otherwise. */
    public record Start(boolean ok, String code, String url, int expiresInSec, Error error) {
        static Start of(Error error) {
            return new Start(false, "", "", 0, error);
        }
    }

    /**
     * Whether this uuid has a Twitch channel linked, which ({@code login} is the twitch.tv handle,
     * {@code display} the name Twitch shows), whether that link is {@code verified} (a Twitch grant, as
     * opposed to a handle typed in chat — shout-out only), and the two opt-ins: {@code restream} (also
     * push the stream to that channel) and {@code setTitle} (set its title/category while live).
     */
    public record Status(boolean ok, boolean linked, boolean verified, String login, String display, boolean restream, boolean setTitle, Error error) {
        public Status {
            login = login == null ? "" : login;
            display = display == null ? "" : display;
        }

        static Status of(Error error) {
            return new Status(false, false, false, "", "", false, false, error);
        }

        /** {@code https://twitch.tv/<login>}, or "" when unlinked. */
        public String url() {
            return linked && !login.isEmpty() ? "https://twitch.tv/" + login : "";
        }
    }

    /** The outcome of an unlink: {@code ok}, or an {@link Error}. */
    public record Unlink(boolean ok, Error error) {}

    private TwitchLinkClient() {}

    /** Mint a code for this player. Resolves off-thread — hop to the render thread before touching a screen. */
    public static CompletableFuture<Start> start() {
        String uuid = ownUuid();
        if (uuid == null) return CompletableFuture.completedFuture(Start.of(Error.NO_CONSENT));
        return post(START_PATH, payload(uuid), TwitchLinkClient::parseStart, Start.of(Error.FAILED), "link start");
    }

    /** Whether this player's Twitch is linked, which channel, and the opt-ins. Resolves off-thread. */
    public static CompletableFuture<Status> status() {
        String uuid = ownUuid();
        if (uuid == null) return CompletableFuture.completedFuture(Status.of(Error.NO_CONSENT));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(RelayTarget.live() + STATUS_PATH + "?uuid=" + uuid))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
            return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> parseStatus(resp.statusCode(), resp.body()))
                .exceptionally(t -> {
                    LOGGER.debug("[DungeonTrain] Twitch: link status failed — {}", t.toString());
                    return Status.of(Error.FAILED);
                });
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(Status.of(Error.FAILED));
        }
    }

    /** Flip one or both opt-ins; {@code null} leaves that one as it is. Resolves off-thread with the new state. */
    public static CompletableFuture<Status> setSettings(@Nullable Boolean restream, @Nullable Boolean setTitle) {
        String uuid = ownUuid();
        if (uuid == null) return CompletableFuture.completedFuture(Status.of(Error.NO_CONSENT));
        JsonObject body = payload(uuid);
        if (restream != null) body.addProperty("restream", restream);
        if (setTitle != null) body.addProperty("setTitle", setTitle);
        return post(SETTINGS_PATH, body, TwitchLinkClient::parseStatus, Status.of(Error.FAILED), "settings");
    }

    /**
     * Save a handle the player typed in chat — the shout-out path, no Twitch grant. The relay checks
     * the channel exists; a verified link is never replaced this way. Resolves off-thread.
     */
    public static CompletableFuture<Status> claim(String login) {
        String uuid = ownUuid();
        if (uuid == null) return CompletableFuture.completedFuture(Status.of(Error.NO_CONSENT));
        JsonObject body = payload(uuid);
        body.addProperty("login", login == null ? "" : login);
        return post(CLAIM_PATH, body, TwitchLinkClient::parseClaim, Status.of(Error.FAILED), "claim");
    }

    /** Forget the link (the relay also tells Twitch to drop the grant). Resolves off-thread. */
    public static CompletableFuture<Unlink> unlink() {
        String uuid = ownUuid();
        if (uuid == null) return CompletableFuture.completedFuture(new Unlink(false, Error.NO_CONSENT));
        return post(UNLINK_PATH, payload(uuid), TwitchLinkClient::parseUnlink, new Unlink(false, Error.FAILED), "unlink");
    }

    private interface Parser<T> { T parse(int status, String body); }

    private static <T> CompletableFuture<T> post(String path, JsonObject body, Parser<T> parser, T failed, String what) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(RelayTarget.live() + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
            return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> parser.parse(resp.statusCode(), resp.body()))
                .exceptionally(t -> {
                    LOGGER.debug("[DungeonTrain] Twitch: {} failed — {}", what, t.toString());
                    return failed;
                });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Twitch: {} could not begin — {}", what, t.toString());
            return CompletableFuture.completedFuture(failed);
        }
    }

    /** This player's undashed profile uuid, or {@code null} without a signed-in user or consent. */
    private static String ownUuid() {
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null || !RelayChatClient.canConnect()) return null;
        return uuid.toString().replace("-", "");
    }

    /** The common {@code {uuid}} body. */
    static JsonObject payload(String uuid) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid == null ? "" : uuid);
        return body;
    }

    /** 2xx with a code and page URL succeeds; 429 is the hourly budget; 503 is the feature switched off; a missing endpoint is an older relay. */
    static Start parseStart(int status, String body) {
        if (status / 100 == 2) {
            JsonObject o = object(body);
            String code = o == null ? "" : str(o.get("code")).trim();
            String url = o == null ? "" : str(o.get("url")).trim();
            if (code.isEmpty() || !url.startsWith("https://")) return Start.of(Error.FAILED);
            return new Start(true, code, url, o.has("expiresInSec") ? num(o.get("expiresInSec")) : 0, Error.NONE);
        }
        return Start.of(errorFor(status, body));
    }

    /** 2xx with a readable {@code linked} succeeds — an unreadable body is never taken as linked. */
    static Status parseStatus(int status, String body) {
        if (status / 100 == 2) {
            JsonObject o = object(body);
            JsonElement linked = o == null ? null : o.get("linked");
            if (linked == null || !linked.isJsonPrimitive() || !linked.getAsJsonPrimitive().isBoolean()) {
                return Status.of(Error.FAILED);
            }
            boolean on = linked.getAsBoolean();
            String login = on ? str(o.get("login")).trim().toLowerCase() : "";
            if (on && !login.matches("[a-z0-9_]{3,25}")) return Status.of(Error.FAILED);
            return new Status(true, on, on && bool(o.get("verified")), login, on ? str(o.get("display")).trim() : "",
                on && bool(o.get("restream")), on && bool(o.get("setTitle")), Error.NONE);
        }
        return Status.of(errorFor(status, body));
    }

    /** A saved typed handle: 2xx with a login → linked, unverified, opt-ins off. */
    static Status parseClaim(int status, String body) {
        if (status / 100 == 2) {
            JsonObject o = object(body);
            String login = o == null ? "" : str(o.get("login")).trim().toLowerCase();
            if (!login.matches("[a-z0-9_]{3,25}")) return Status.of(Error.FAILED);
            return new Status(true, true, false, login, str(o.get("display")).trim(), false, false, Error.NONE);
        }
        return Status.of(errorFor(status, body));
    }

    static Unlink parseUnlink(int status, String body) {
        if (status / 100 == 2) return new Unlink(true, Error.NONE);
        return new Unlink(false, errorFor(status, body));
    }

    /** 404 is "no link" / "no such channel" only when the relay says so — an older relay 404s the path too. */
    private static Error errorFor(int status, String body) {
        if (status == 429) return Error.RATE_LIMITED;
        if (status == 503) return Error.DISABLED;
        JsonObject o = object(body);
        String code = o == null ? "" : str(o.get("error"));
        if (status == 404) {
            if ("not_linked".equals(code)) return Error.NOT_LINKED;
            if ("unknown_channel".equals(code)) return Error.UNKNOWN_CHANNEL;
            return Error.UNSUPPORTED;
        }
        if (status == 403 && "not_verified".equals(code)) return Error.NOT_VERIFIED;
        if (status == 409 && "already_verified".equals(code)) return Error.ALREADY_VERIFIED;
        if (status == 400 && "bad_login".equals(code)) return Error.BAD_LOGIN;
        if (ENDPOINT_MISSING.contains(status)) return Error.UNSUPPORTED;
        return Error.FAILED;
    }

    private static boolean bool(JsonElement el) {
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean() && el.getAsBoolean();
    }

    private static JsonObject object(String body) {
        try {
            JsonElement root = JsonParser.parseString(body == null ? "" : body);
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JsonElement el) {
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : "";
    }

    private static int num(JsonElement el) {
        try {
            return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber() ? el.getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
