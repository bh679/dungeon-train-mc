package games.brennan.dungeontrain.client.credits;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.chat.RelayChatClient;
import games.brennan.dungeontrain.discord.PingType;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The relay half of tying this player's Discord account to their Minecraft uuid, so their line on
 * the Credits page's Community card gets an Edit button ({@link DiscordLinkScreen}) and the relay
 * @-pings them on their own photos and death reports ({@code /discord}, {@link DiscordLinkCommand}).
 *
 * <p>Three calls. {@link #start} asks {@code POST /community/link/start} for a short code the player
 * types into Discord as {@code /dtlink <code>}; {@link #status} asks
 * {@code GET /community/link/status?uuid=} whether that has happened yet (and whether pings are on);
 * {@link #setPings} is {@code POST /community/link/pings}, the {@code /discord pings} toggle. The relay is the only
 * party that can see the Discord side (the command reaches it as an interaction from that user),
 * so the mod never learns a Discord id — only "linked" or not.</p>
 *
 * <p>Both carry the player's uuid, so both are consent-gated the way every other credits call is.
 * Live pool only: the Community card is read from there. HTTP/1.1-pinned, never blocking, no-throw
 * at every entry point; the parsing is pure so it can be tested without a relay.</p>
 */
public final class CommunityLinkClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    static final String START_PATH = "/community/link/start";
    static final String STATUS_PATH = "/community/link/status";
    static final String PINGS_PATH = "/community/link/pings";
    /** An older relay (or one without the feature): 404/405/501 on the path. */
    private static final Set<Integer> ENDPOINT_MISSING = Set.of(404, 405, 501);

    /** Why a call did not do what was asked. {@code NONE} on success. */
    public enum Error { NONE, NO_CONSENT, RATE_LIMITED, UNSUPPORTED, DISABLED, NOT_LINKED, FAILED }

    /** A minted code and how long it lives; {@code ok} false with an {@link Error} otherwise. */
    public record Start(boolean ok, String code, int expiresInSec, Error error) {
        static Start of(Error error) {
            return new Start(false, "", 0, error);
        }
    }

    /**
     * Whether the relay has seen {@code /dtlink} for this uuid, whether that link is @-pinged at all
     * ({@code pings}, the master switch) and on which kinds of post ({@code types} — every
     * {@link PingType} present, on unless turned off), plus the linked account's Discord display name
     * ({@code ""} when unknown); {@code ok} false with an {@link Error} otherwise.
     */
    public record Status(boolean ok, boolean linked, boolean pings, Map<PingType, Boolean> types,
                         String discordName, Error error) {
        public Status {
            types = Collections.unmodifiableMap(new EnumMap<>(withDefaults(types)));
            discordName = discordName == null ? "" : discordName;
        }

        static Status of(Error error) {
            return new Status(false, false, false, Map.of(), "", error);
        }

        /** Whether this kind of post pings — the master switch and the kind both on. */
        public boolean pings(PingType type) {
            return pings && types.getOrDefault(type, true);
        }
    }

    /**
     * The outcome of a pings toggle: {@code ok} with the master switch ({@code on}) and every kind as
     * they now stand, or an {@link Error}.
     */
    public record Pings(boolean ok, boolean on, Map<PingType, Boolean> types, Error error) {
        public Pings {
            types = Collections.unmodifiableMap(new EnumMap<>(withDefaults(types)));
        }

        static Pings of(Error error) {
            return new Pings(false, false, Map.of(), error);
        }
    }

    /** {@code types} with every {@link PingType} present — a kind the relay didn't mention is on. */
    private static Map<PingType, Boolean> withDefaults(Map<PingType, Boolean> types) {
        Map<PingType, Boolean> out = new EnumMap<>(PingType.class);
        for (PingType t : PingType.values()) out.put(t, types == null || types.getOrDefault(t, true));
        return out;
    }

    private CommunityLinkClient() {}

    /** Mint a code for this player. Resolves off-thread — hop to the render thread before touching a screen. */
    public static CompletableFuture<Start> start() {
        String uuid = ownUuid();
        if (uuid == null) return CompletableFuture.completedFuture(Start.of(Error.NO_CONSENT));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(RelayTarget.live() + START_PATH))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildStartPayload(uuid).toString()))
                .build();
            return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> parseStart(resp.statusCode(), resp.body()))
                .exceptionally(t -> {
                    LOGGER.debug("[DungeonTrain] Credits: link start failed — {}", t.toString());
                    return Start.of(Error.FAILED);
                });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Credits: link start could not begin — {}", t.toString());
            return CompletableFuture.completedFuture(Start.of(Error.FAILED));
        }
    }

    /** Has the player run the command yet? Resolves off-thread. */
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
                    LOGGER.debug("[DungeonTrain] Credits: link status failed — {}", t.toString());
                    return Status.of(Error.FAILED);
                });
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(Status.of(Error.FAILED));
        }
    }

    /** Turn the relay's @-pings on or off (the master switch) for this player's linked Discord. Resolves off-thread. */
    public static CompletableFuture<Pings> setPings(boolean on) {
        return postPings(on, null);
    }

    /** Turn one kind of ping on or off for this player's linked Discord. Resolves off-thread. */
    public static CompletableFuture<Pings> setPingType(PingType type, boolean on) {
        return postPings(on, type);
    }

    private static CompletableFuture<Pings> postPings(boolean on, PingType type) {
        String uuid = ownUuid();
        if (uuid == null) return CompletableFuture.completedFuture(Pings.of(Error.NO_CONSENT));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(RelayTarget.live() + PINGS_PATH))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildPingsPayload(uuid, on, type).toString()))
                .build();
            return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> parsePings(resp.statusCode(), resp.body()))
                .exceptionally(t -> {
                    LOGGER.debug("[DungeonTrain] Discord: pings toggle failed — {}", t.toString());
                    return Pings.of(Error.FAILED);
                });
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(Pings.of(Error.FAILED));
        }
    }

    /** This player's undashed profile uuid, or {@code null} without a signed-in user or consent. */
    private static String ownUuid() {
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null || !RelayChatClient.canConnect()) return null;
        return uuid.toString().replace("-", "");
    }

    /** The {@code /community/link/start} body. */
    static JsonObject buildStartPayload(String uuid) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid == null ? "" : uuid);
        return body;
    }

    /** The {@code /community/link/pings} body; {@code type} null for the master switch. */
    static JsonObject buildPingsPayload(String uuid, boolean on, PingType type) {
        JsonObject body = buildStartPayload(uuid);
        body.addProperty("on", on);
        if (type != null) body.addProperty("type", type.wireId());
        return body;
    }

    /** 2xx with a code succeeds; 429 is the hourly budget; 503 is the feature switched off; a missing endpoint is an older relay. */
    static Start parseStart(int status, String body) {
        if (status / 100 == 2) {
            JsonObject o = object(body);
            String code = o == null ? "" : str(o.get("code")).trim();
            if (code.isEmpty()) return Start.of(Error.FAILED);
            return new Start(true, code, o.has("expiresInSec") ? num(o.get("expiresInSec")) : 0, Error.NONE);
        }
        if (status == 429) return Start.of(Error.RATE_LIMITED);
        if (status == 503) return Start.of(Error.DISABLED);
        if (ENDPOINT_MISSING.contains(status)) return Start.of(Error.UNSUPPORTED);
        return Start.of(Error.FAILED);
    }

    /** 2xx with a readable {@code linked} succeeds — an unreadable body is never taken as linked. */
    static Status parseStatus(int status, String body) {
        if (status / 100 == 2) {
            JsonObject o = object(body);
            JsonElement linked = o == null ? null : o.get("linked");
            if (linked == null || !linked.isJsonPrimitive() || !linked.getAsJsonPrimitive().isBoolean()) {
                return Status.of(Error.FAILED);
            }
            return new Status(true, linked.getAsBoolean(), linked.getAsBoolean() && bool(o.get("pings")),
                    types(o.get("types")), linked.getAsBoolean() ? str(o.get("discordName")).trim() : "", Error.NONE);
        }
        if (status == 429) return Status.of(Error.RATE_LIMITED);
        if (status == 503) return Status.of(Error.DISABLED);
        if (ENDPOINT_MISSING.contains(status)) return Status.of(Error.UNSUPPORTED);
        return Status.of(Error.FAILED);
    }

    /** 2xx with a readable {@code pings} succeeds; 404 is a uuid with no linked Discord account. */
    static Pings parsePings(int status, String body) {
        if (status / 100 == 2) {
            JsonObject o = object(body);
            JsonElement on = o == null ? null : o.get("pings");
            if (on == null || !on.isJsonPrimitive() || !on.getAsJsonPrimitive().isBoolean()) return Pings.of(Error.FAILED);
            return new Pings(true, on.getAsBoolean(), types(o.get("types")), Error.NONE);
        }
        if (status == 404) {
            JsonObject o = object(body);
            // A relay that predates the toggle 404s the path too — only its own error means unlinked.
            return Pings.of(o != null && "not_linked".equals(str(o.get("error"))) ? Error.NOT_LINKED : Error.UNSUPPORTED);
        }
        if (status == 503) return Pings.of(Error.DISABLED);
        if (ENDPOINT_MISSING.contains(status)) return Pings.of(Error.UNSUPPORTED);
        return Pings.of(Error.FAILED);
    }

    /** {@code {"death": false, ...}} → per-kind map; unknown kinds and non-booleans are ignored (left on). */
    static Map<PingType, Boolean> types(JsonElement el) {
        Map<PingType, Boolean> out = new EnumMap<>(PingType.class);
        if (el == null || !el.isJsonObject()) return out;
        for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
            JsonElement v = e.getValue();
            if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) continue;
            PingType.byWireId(e.getKey()).ifPresent(t -> out.put(t, v.getAsBoolean()));
        }
        return out;
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
