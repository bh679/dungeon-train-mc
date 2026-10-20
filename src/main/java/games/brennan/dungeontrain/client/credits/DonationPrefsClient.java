package games.brennan.dungeontrain.client.credits;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.client.chat.RelayChatClient;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * How the player's donations are announced on Discord — the relay's {@code /donation/prefs}, set from
 * the Account tab of Options → Dungeon Train. The relay is the source of truth and applies the choice
 * when a donation's checkout name matches this player.
 *
 * <ul>
 *   <li>{@link Visibility}: public (default) / anonymous / private (the developer only) / off.</li>
 *   <li>{@code tag}: whether their Discord account is tagged in the message (default on).</li>
 * </ul>
 *
 * <p>Every call resolves off-thread and never throws; failures come back as {@code ok == false}.</p>
 */
public final class DonationPrefsClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final String PATH = "/donation/prefs";

    /** Where a donation message goes. Wire ids match the relay's {@code VISIBILITIES}. */
    public enum Visibility {
        PUBLIC("public"), ANONYMOUS("anonymous"), PRIVATE("private"), OFF("off");

        private final String wireId;

        Visibility(String wireId) {
            this.wireId = wireId;
        }

        public String wireId() {
            return wireId;
        }

        /** The lang-key suffix — same as the wire id. */
        public String key() {
            return wireId;
        }

        /** The value for a wire id, or {@code null} for anything the relay shouldn't have sent. */
        public static Visibility byWireId(String id) {
            if (id == null) return null;
            String norm = id.trim().toLowerCase(Locale.ROOT);
            for (Visibility v : values()) {
                if (v.wireId.equals(norm)) return v;
            }
            return null;
        }
    }

    /**
     * The relay's answer; {@code ok == false} means nothing usable came back. {@code currency} is the
     * ISO code the player views gift amounts in, or {@code null} when they never picked one (and
     * against a relay that predates it).
     */
    public record Prefs(boolean ok, Visibility visibility, boolean tag, String currency) {
        static final Prefs FAILED = new Prefs(false, Visibility.PUBLIC, true, null);

        public Prefs(boolean ok, Visibility visibility, boolean tag) {
            this(ok, visibility, tag, null);
        }
    }

    private DonationPrefsClient() {}

    /** The player's current choice. Resolves off-thread; {@link Prefs#FAILED} without consent or on failure. */
    public static CompletableFuture<Prefs> get() {
        String uuid = ownUuid();
        if (uuid == null) return CompletableFuture.completedFuture(Prefs.FAILED);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(RelayTarget.live() + PATH + "?uuid=" + uuid))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
            return send(req);
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(Prefs.FAILED);
        }
    }

    /** Change where donation messages go. */
    public static CompletableFuture<Prefs> setVisibility(Visibility visibility) {
        return post(buildPayload(ownUuid(), visibility, null));
    }

    /** Change whether the player's Discord is tagged. */
    public static CompletableFuture<Prefs> setTag(boolean tag) {
        return post(buildPayload(ownUuid(), null, tag));
    }

    /**
     * The currency the player last picked on any of their installs, for a fresh install to adopt.
     * Gated on network consent alone (the Account choices above also need dev chat on): the currency
     * is a display preference, not a Discord one.
     */
    public static CompletableFuture<Prefs> getCurrency() {
        String uuid = consentedUuid();
        if (uuid == null) return CompletableFuture.completedFuture(Prefs.FAILED);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(RelayTarget.live() + PATH + "?uuid=" + uuid))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
            return send(req);
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(Prefs.FAILED);
        }
    }

    /** Store the picked currency against the player's account (no-op without network consent). */
    public static CompletableFuture<Prefs> setCurrency(String code) {
        return post(buildCurrencyPayload(consentedUuid(), code));
    }

    private static CompletableFuture<Prefs> post(JsonObject body) {
        if (body == null) return CompletableFuture.completedFuture(Prefs.FAILED);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(RelayTarget.live() + PATH))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
            return send(req);
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(Prefs.FAILED);
        }
    }

    private static CompletableFuture<Prefs> send(HttpRequest req) {
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> parse(resp.statusCode(), resp.body()))
            .exceptionally(t -> {
                LOGGER.debug("[DungeonTrain] Donation prefs request failed — {}", t.toString());
                return Prefs.FAILED;
            });
    }

    /** This player's undashed profile uuid, or {@code null} without a signed-in user or consent. */
    private static String ownUuid() {
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null || !RelayChatClient.canConnect()) return null;
        return uuid.toString().replace("-", "");
    }

    /** This player's undashed profile uuid, or {@code null} without a signed-in user or network consent. */
    private static String consentedUuid() {
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null || !DiscordPresenceClientConfig.isGranted()) return null;
        return uuid.toString().replace("-", "");
    }

    /** The currency POST body; {@code null} without a uuid or a code. */
    static JsonObject buildCurrencyPayload(String uuid, String code) {
        if (uuid == null || uuid.isEmpty() || code == null || code.isBlank()) return null;
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        body.addProperty("currency", code);
        return body;
    }

    /** The POST body; {@code null} without a uuid. Omitted fields keep their stored value on the relay. */
    static JsonObject buildPayload(String uuid, Visibility visibility, Boolean tag) {
        if (uuid == null || uuid.isEmpty()) return null;
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        if (visibility != null) body.addProperty("visibility", visibility.wireId());
        if (tag != null) body.addProperty("tag", tag);
        return body;
    }

    /** 2xx with a known {@code visibility} and a boolean {@code tag} succeeds; anything else fails. */
    static Prefs parse(int status, String body) {
        if (status / 100 != 2 || body == null) return Prefs.FAILED;
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return Prefs.FAILED;
            JsonObject o = root.getAsJsonObject();
            JsonElement v = o.get("visibility");
            JsonElement t = o.get("tag");
            Visibility vis = v != null && v.isJsonPrimitive() ? Visibility.byWireId(v.getAsString()) : null;
            if (vis == null || t == null || !t.isJsonPrimitive() || !t.getAsJsonPrimitive().isBoolean()) {
                return Prefs.FAILED;
            }
            JsonElement c = o.get("currency");
            String currency = c != null && c.isJsonPrimitive() ? c.getAsString() : null;
            return new Prefs(true, vis, t.getAsBoolean(), currency);
        } catch (RuntimeException e) {
            return Prefs.FAILED;
        }
    }
}
