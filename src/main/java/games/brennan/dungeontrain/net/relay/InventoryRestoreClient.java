package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Asks the relay whether an admin has queued a one-time inventory restore for a player, and
 * acknowledges one once it has been applied.
 *
 * <pre>
 * GET  &lt;relayBase&gt;/restore/pending?uuid=&lt;no-dash uuid&gt;
 *   200 { "ok": true, "restore": { "id": "r_…", "inventory": [Stack], "armor": [Stack],
 *                                  "offhand": [Stack], "xpLevels": 32 } }
 *   200 { "ok": true, "restore": null }
 *   Stack = { "slot": 0, "id": "minecraft:…", "count": 1, "nbt": "&lt;SNBT&gt;" }
 * POST /restore/ack  { uuid, id, state: "applied"|"declined", reason, placed, displaced, skipped }
 * </pre>
 *
 * <p>Restores are queued by hand on the relay's admin explorer, only after Brennan has seen a bug
 * cost a player their items. Same fire-and-forget shape as {@link DebugGrantClient}: the callback
 * fires only for a well-formed 2xx answer, so a relay outage simply means "ask again later".</p>
 */
public final class InventoryRestoreClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String ACK_PATH = "/restore/ack";

    // Pin HTTP/1.1 for local cleartext testing — see BookStatsClient for the rationale.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private InventoryRestoreClient() {}

    /** One stack as the relay stores it. {@code nbt} is authoritative; {@code id}/{@code count} are for logs. */
    public record Stack(int slot, String id, int count, String nbt) {}

    /** A pending restore. {@code xpLevels} is negative when the admin didn't set one. */
    public record Restore(String id, List<Stack> inventory, List<Stack> armor, List<Stack> offhand, int xpLevels) {
        public List<Stack> all() {
            List<Stack> out = new ArrayList<>(inventory);
            out.addAll(armor);
            out.addAll(offhand);
            return out;
        }
    }

    /**
     * Fetch {@code player}'s pending restore off-thread. {@code callback} runs on the HTTP thread,
     * only when the relay names a usable restore. The caller hops to the server thread. No-throw.
     */
    public static void fetch(UUID player, Consumer<Restore> callback) {
        try {
            String url = DungeonTrain.relayBaseUrl() + "/restore/pending?uuid=" + noDashes(player);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] restore fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] restore fetch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            Restore restore = parse(resp.body());
                            if (restore != null) callback.accept(restore);
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] restore parse failed: {}", t.toString());
                        }
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] restore request failed to start: {}", t.toString());
        }
    }

    /** What happened to a restore, as reported back to the relay. */
    public record Outcome(boolean applied, String reason, int placed, int displaced, int skipped) {}

    /** Queue the answer for delivery through {@link RelayOutbox}, which retries. Never throws. */
    public static void ack(UUID player, String id, Outcome outcome) {
        RelayOutbox.get().enqueue(ACK_PATH, ackBody(player, id, outcome));
    }

    static String ackBody(UUID player, String id, Outcome outcome) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", noDashes(player));
        o.addProperty("id", id);
        o.addProperty("state", outcome.applied() ? "applied" : "declined");
        if (outcome.reason() != null && !outcome.reason().isBlank()) o.addProperty("reason", outcome.reason());
        o.addProperty("placed", outcome.placed());
        o.addProperty("displaced", outcome.displaced());
        o.addProperty("skipped", outcome.skipped());
        return o.toString();
    }

    /**
     * Parse the relay body, or null when there is no usable restore — none pending, or a shape we
     * don't understand. Package-private so the wire contract is pinned in a test.
     */
    static Restore parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        if (!o.has("ok") || !o.get("ok").getAsBoolean()) return null;
        if (!o.has("restore") || !o.get("restore").isJsonObject()) return null;
        JsonObject r = o.getAsJsonObject("restore");
        if (!r.has("id") || !r.get("id").isJsonPrimitive()) return null;
        String id = r.get("id").getAsString();
        if (id.isBlank()) return null;
        int xp = r.has("xpLevels") && r.get("xpLevels").isJsonPrimitive() ? r.get("xpLevels").getAsInt() : -1;
        Restore restore = new Restore(id, stacks(r, "inventory"), stacks(r, "armor"), stacks(r, "offhand"), xp);
        return restore.all().isEmpty() && xp < 0 ? null : restore;
    }

    /** Stacks under {@code key}; an entry missing its slot or SNBT is dropped, never guessed at. */
    private static List<Stack> stacks(JsonObject r, String key) {
        List<Stack> out = new ArrayList<>();
        if (!r.has(key) || !r.get(key).isJsonArray()) return out;
        JsonArray a = r.getAsJsonArray(key);
        for (JsonElement e : a) {
            if (!e.isJsonObject()) continue;
            JsonObject s = e.getAsJsonObject();
            if (!s.has("slot") || !s.has("nbt") || !s.get("nbt").isJsonPrimitive()) continue;
            String id = s.has("id") && s.get("id").isJsonPrimitive() ? s.get("id").getAsString() : "?";
            int count = s.has("count") && s.get("count").isJsonPrimitive() ? s.get("count").getAsInt() : 1;
            out.add(new Stack(s.get("slot").getAsInt(), id, count, s.get("nbt").getAsString()));
        }
        return out;
    }

    static String noDashes(UUID uuid) {
        return uuid.toString().replace("-", "");
    }
}
