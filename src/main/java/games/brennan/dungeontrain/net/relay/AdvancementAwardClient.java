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
 * Asks the relay which advancements the developer has given a player, and tells it once they are earned:
 *
 * <pre>
 * GET  &lt;relayBase&gt;/advancements/award?uuid=&lt;mc-uuid, no dashes&gt;
 *   200 { "ok": true, "awards": [ { "id": "a_1a2b3c4d5e6f", "adv": "ns:path" } ] }
 * POST &lt;relayBase&gt;/advancements/award/ack   (through {@link RelayOutbox}, so it is retried)
 *   { "uuid": "…", "ids": [ "a_1a2b3c4d5e6f" ] }
 * </pre>
 *
 * <p>The mirror of {@link AdvancementRevokeClient}. An award is served until it is acked; the relay then
 * marks it applied and stops serving it, so each is earned once.</p>
 *
 * <p>The same fire-and-forget shape: own {@link HttpClient}, HTTP/1.1 pinned, every failure swallowed and
 * logged at debug. The callback fires only for a well-formed answer — a relay outage or an older relay
 * without the route means "nothing to award".</p>
 */
public final class AdvancementAwardClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String ACK_PATH = "/advancements/award/ack";

    // Pin HTTP/1.1 for local cleartext testing — see BookStatsClient for the rationale.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** One award: its relay id and the advancement to grant. */
    public record Award(String id, ResourceLocation adv) {}

    private AdvancementAwardClient() {}

    /**
     * Fetch {@code player}'s pending awards off-thread. {@code callback} runs on the HTTP completion thread
     * with the full list (possibly empty) when the relay answered well-formed; the caller hops to the
     * server thread. No-throw.
     */
    public static void fetch(UUID player, Consumer<List<Award>> callback) {
        try {
            String url = DungeonTrain.relayBaseUrl() + "/advancements/award?uuid=" + noDashes(player);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] award fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] award fetch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            List<Award> parsed = parse(resp.body());
                            if (parsed != null) callback.accept(parsed);
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] award parse failed: {}", t.toString());
                        }
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] award request failed to start: {}", t.toString());
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
     * Parse the relay body: the list of awards, or null when the body isn't a well-formed answer. An entry
     * with a missing id or an unparseable advancement id is skipped, never guessed at. Package-private so
     * the wire shape is pinned in a test.
     */
    static List<Award> parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        if (!o.has("ok") || !o.get("ok").isJsonPrimitive() || !o.get("ok").getAsBoolean()) return null;
        if (!o.has("awards") || !o.get("awards").isJsonArray()) return null;
        List<Award> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("awards")) {
            Award a = award(e);
            if (a != null) out.add(a);
        }
        return List.copyOf(out);
    }

    private static Award award(JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject a = e.getAsJsonObject();
        String id = string(a, "id");
        String adv = string(a, "adv");
        ResourceLocation parsed = adv == null ? null : ResourceLocation.tryParse(adv);
        if (id == null || id.isBlank() || parsed == null) return null;
        return new Award(id, parsed);
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static String noDashes(UUID uuid) {
        return uuid.toString().replace("-", "");
    }
}
