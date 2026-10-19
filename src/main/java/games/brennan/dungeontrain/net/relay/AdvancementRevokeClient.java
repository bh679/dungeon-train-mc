package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Asks the relay which advancements the developer has taken back from a player, and tells it once
 * they have been removed:
 *
 * <pre>
 * GET  &lt;relayBase&gt;/advancements/revoke?uuid=&lt;mc-uuid, no dashes&gt;
 *   200 { "ok": true, "revokes": [ { "id": "r_1a2b3c4d5e6f", "adv": "ns:path", "ts": 1790000000000 } ] }
 * POST &lt;relayBase&gt;/advancements/revoke/ack   (through {@link RelayOutbox}, so it is retried)
 *   { "uuid": "…", "ids": [ "r_1a2b3c4d5e6f" ] }
 * </pre>
 *
 * <p>Every ACTIVE revocation is served on every request, so the list is the whole truth: a revocation
 * the developer cancelled simply stops appearing. {@code ts} is when it was made — only earns at or
 * before it are taken back ({@link games.brennan.dungeontrain.advancement.AdvancementRevocations}).</p>
 *
 * <p>The same fire-and-forget shape as {@link AdvancementPromoteClient}: own {@link HttpClient}, HTTP/1.1
 * pinned, every failure swallowed and logged at debug. The callback fires only for a well-formed answer
 * — a relay outage or an older relay without the route means "nothing new", never "nothing revoked".</p>
 */
public final class AdvancementRevokeClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String ACK_PATH = "/advancements/revoke/ack";

    // Pin HTTP/1.1 for local cleartext testing — see BookStatsClient for the rationale.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** One revocation: its relay id, the advancement, and the time (epoch ms) earns must predate. */
    public record Revoke(String id, ResourceLocation adv, long ts) {}

    private AdvancementRevokeClient() {}

    /**
     * Fetch {@code player}'s active revocations off-thread. {@code callback} runs on the HTTP completion
     * thread with the full list (possibly empty) when the relay answered well-formed; the caller hops to
     * the server thread. No-throw.
     */
    public static void fetch(UUID player, Consumer<List<Revoke>> callback) {
        try {
            String url = DungeonTrain.relayBaseUrl() + "/advancements/revoke?uuid=" + noDashes(player);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] revoke fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] revoke fetch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            List<Revoke> parsed = parse(resp.body());
                            if (parsed != null) callback.accept(parsed);
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] revoke parse failed: {}", t.toString());
                        }
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] revoke request failed to start: {}", t.toString());
        }
    }

    /** Queue the ack for delivery. Never throws; nothing to send for an empty list. */
    public static void ack(UUID player, Collection<String> ids) {
        if (ids.isEmpty()) return;
        RelayOutbox.get().enqueue(ACK_PATH, ackBody(player, ids));
    }

    static String ackBody(UUID player, Collection<String> ids) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", noDashes(player));
        JsonArray a = new JsonArray();
        for (String id : ids) a.add(id);
        o.add("ids", a);
        return o.toString();
    }

    /**
     * Parse the relay body: the list of revocations, or null when the body isn't a well-formed answer.
     * An entry with a missing id, an unparseable advancement id or no time is skipped, never guessed
     * at. Package-private so the wire shape is pinned in a test.
     */
    static List<Revoke> parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        if (!o.has("ok") || !o.get("ok").isJsonPrimitive() || !o.get("ok").getAsBoolean()) return null;
        if (!o.has("revokes") || !o.get("revokes").isJsonArray()) return null;
        List<Revoke> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("revokes")) {
            Revoke r = revoke(e);
            if (r != null) out.add(r);
        }
        return List.copyOf(out);
    }

    /** One entry, or null when any field is missing or malformed. Shared with the on-disk cache. */
    public static Revoke revoke(JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject r = e.getAsJsonObject();
        String id = string(r, "id");
        ResourceLocation adv = ResourceLocation.tryParse(string(r, "adv") == null ? "" : string(r, "adv"));
        if (id == null || id.isBlank() || adv == null || !r.has("ts") || !r.get("ts").isJsonPrimitive()) return null;
        long ts;
        try {
            ts = r.get("ts").getAsLong();
        } catch (NumberFormatException ex) {
            return null;
        }
        return ts > 0 ? new Revoke(id, adv, ts) : null;
    }

    /** The cache's JSON form of {@code r}; {@link #revoke} reads it back. */
    public static JsonObject json(Revoke r) {
        JsonObject o = new JsonObject();
        o.addProperty("id", r.id());
        o.addProperty("adv", r.adv().toString());
        o.addProperty("ts", r.ts());
        return o;
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static String noDashes(UUID uuid) {
        return uuid.toString().replace("-", "");
    }
}
