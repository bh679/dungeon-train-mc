package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.AdvancementPromotion;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Asks the relay whether the developer has sent this player a one-time advancement promote, and tells
 * it when the player has answered:
 *
 * <pre>
 * GET  &lt;relayBase&gt;/advancements/promote?uuid=&lt;mc-uuid, no dashes&gt;
 *   200 { "ok": true, "promote": { "id": "p_1a2b", "target": "hard", "mode": "copy" } }
 *   200 { "ok": true, "promote": null }
 * POST &lt;relayBase&gt;/advancements/promote/ack   (through {@link RelayOutbox}, so it is retried)
 *   { "uuid": "…", "id": "p_1a2b", "state": "done"|"declined", "copied": [ids], "moved": [ids] }
 * </pre>
 *
 * <p>The same fire-and-forget shape as {@link DebugGrantClient}: own {@link HttpClient}, HTTP/1.1
 * pinned, every failure swallowed and logged at debug, and the callback fires only for a well-formed
 * promote — a relay outage or an older relay without the route simply means no popup.</p>
 */
public final class AdvancementPromoteClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String ACK_PATH = "/advancements/promote/ack";

    // Pin HTTP/1.1 for local cleartext testing — see BookStatsClient for the rationale.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** A pending promote: where it goes, and whether the developer allowed per-advancement copy. */
    public record Promote(String id, String target, AdvancementPromotion.Mode mode) {}

    private AdvancementPromoteClient() {}

    /**
     * Fetch {@code player}'s pending promote off-thread. {@code callback} runs on the HTTP completion
     * thread and only when there is one; the caller hops to the render thread. No-throw.
     */
    public static void fetch(UUID player, Consumer<Promote> callback) {
        try {
            String url = DungeonTrain.relayBaseUrl() + "/advancements/promote?uuid=" + noDashes(player);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] promote fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] promote fetch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            Promote parsed = parse(resp.body());
                            if (parsed != null) callback.accept(parsed);
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] promote parse failed: {}", t.toString());
                        }
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] promote request failed to start: {}", t.toString());
        }
    }

    /** Queue the answer for delivery. Never throws. */
    public static void ack(UUID player, String id, boolean done,
                           Collection<ResourceLocation> copied, Collection<ResourceLocation> moved) {
        RelayOutbox.get().enqueue(ACK_PATH, ackBody(player, id, done, copied, moved));
    }

    static String ackBody(UUID player, String id, boolean done,
                          Collection<ResourceLocation> copied, Collection<ResourceLocation> moved) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", noDashes(player));
        o.addProperty("id", id);
        o.addProperty("state", done ? "done" : "declined");
        o.add("copied", ids(copied));
        o.add("moved", ids(moved));
        return o.toString();
    }

    private static JsonArray ids(Collection<ResourceLocation> ids) {
        JsonArray a = new JsonArray();
        for (ResourceLocation id : ids) a.add(id.toString());
        return a;
    }

    /**
     * Parse the relay body, or null when there is no usable promote — none pending, or a field we
     * don't understand (an unknown difficulty or mode is never guessed at). Package-private so the
     * wire shape is pinned in a test.
     */
    static Promote parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        if (!o.has("ok") || !o.get("ok").getAsBoolean()) return null;
        if (!o.has("promote") || !o.get("promote").isJsonObject()) return null;
        JsonObject p = o.getAsJsonObject("promote");
        String id = string(p, "id");
        String target = string(p, "target");
        AdvancementPromotion.Mode mode = AdvancementPromotion.Mode.parse(string(p, "mode"));
        if (id == null || id.isBlank() || mode == null || !isDifficulty(target)) return null;
        return new Promote(id, target, mode);
    }

    private static boolean isDifficulty(String key) {
        if (key == null) return false;
        for (Difficulty d : Difficulty.values()) {
            if (d.getSerializedName().equals(key)) return true;
        }
        return false;
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static String noDashes(UUID uuid) {
        return uuid.toString().replace("-", "");
    }
}
