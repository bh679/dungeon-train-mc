package games.brennan.dungeontrain.narrative;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, per-player cache of the UNSPAWNED Death Note curses targeting each online player,
 * fetched from the relay's {@code /deathnotes} endpoint. The arrival scan ({@code DeathNoteRefreshEvents})
 * reads a player's snapshot on the server thread; the fetch is fire-and-forget off-thread (its own
 * {@link HttpClient}) and swaps in a fresh immutable list per player when the relay replies.
 *
 * <p>Refreshed at login (every world load) + every ~30 carriages by {@code DeathNoteRefreshEvents},
 * matched on the relay by <em>target only</em> (seed-agnostic — a curse surfaces in the target's next
 * life at the carriage depth the author died at, in any world). Reads never block or touch the network;
 * each swap replaces the per-player reference wholesale.</p>
 */
public final class DeathNotePool {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Max notes requested per fetch (a single target rarely has many). */
    static final int POOL_LIMIT = 50;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // relay is HTTP/1.1; avoids h2c against a bare-Node relay (matches NarrativePool/SharedBookPool/DeathReporter/BookStatsClient)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** One downloaded, not-yet-spawned curse targeting the local player. */
    public record Note(int id, String authorUuid, String authorName, String authorSkinRef,
                       int deathCarriage, String worldKey) {}

    /** targetPlayerUuid → their current immutable snapshot of unspawned notes. */
    private static final Map<UUID, List<Note>> NOTES = new ConcurrentHashMap<>();
    /** Per-player in-flight guard so overlapping refreshes don't stack. */
    private static final Map<UUID, Boolean> IN_FLIGHT = new ConcurrentHashMap<>();

    private DeathNotePool() {}

    /**
     * Notes for {@code playerUuid} whose death carriage the player has reached — {@code cur >=
     * deathCarriage - lead} (a small lead so the echo can be placed just ahead as they approach).
     * Forward-only travel means this fires once the target arrives at (or passes) the death carriage.
     */
    public static List<Note> notesReached(UUID playerUuid, int cur, int lead) {
        List<Note> notes = NOTES.get(playerUuid);
        if (notes == null || notes.isEmpty()) return List.of();
        List<Note> out = new ArrayList<>();
        for (Note n : notes) if (cur >= n.deathCarriage() - lead) out.add(n);
        return out;
    }

    /** True when {@code playerUuid} has any unspawned notes cached (cheap gate for the spawn scan). */
    public static boolean hasAny(UUID playerUuid) {
        List<Note> notes = NOTES.get(playerUuid);
        return notes != null && !notes.isEmpty();
    }

    /** Drop note {@code id} from a player's snapshot after its echo spawns (so it can't respawn locally). */
    public static void remove(UUID playerUuid, int id) {
        NOTES.computeIfPresent(playerUuid, (k, list) -> {
            List<Note> next = new ArrayList<>(list.size());
            for (Note n : list) if (n.id() != id) next.add(n);
            return List.copyOf(next);
        });
    }

    /** Forget a player's cache (on logout). */
    public static void forget(UUID playerUuid) {
        NOTES.remove(playerUuid);
        IN_FLIGHT.remove(playerUuid);
    }

    /**
     * Fetch {@code playerUuid}'s unspawned curses (matched by target, ANY world) off-thread and swap in
     * the new snapshot. No-throw; a failed/slow fetch leaves the existing snapshot in place. Skips if a
     * fetch for this player is already in flight.
     */
    public static void refreshForPlayer(UUID playerUuid, String playerName) {
        if (playerUuid == null) return;
        if (IN_FLIGHT.putIfAbsent(playerUuid, Boolean.TRUE) != null) return;
        try {
            // No &world= — the pull is seed-agnostic (matched by target only), so a curse armed in the
            // author's world surfaces in the target's next, differently-seeded life.
            String url = DungeonTrain.relayBaseUrl()
                    + "/deathnotes?target=" + enc(playerName)
                    + "&uuid=" + playerUuid.toString().replace("-", "")
                    + "&limit=" + POOL_LIMIT;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] death-note fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] death-note fetch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            applyResponse(playerUuid, resp.body());
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] death-note parse failed: {}", t.toString());
                        } finally {
                            IN_FLIGHT.remove(playerUuid);
                        }
                    });
        } catch (Throwable t) {
            IN_FLIGHT.remove(playerUuid);
            LOGGER.debug("[DungeonTrain] death-note refresh failed to start: {}", t.toString());
        }
    }

    /** Parse the relay JSON body and publish {@code playerUuid}'s new immutable snapshot. */
    static void applyResponse(UUID playerUuid, String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return;
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("ok") || !obj.get("ok").getAsBoolean()) return;
        if (!obj.has("notes") || !obj.get("notes").isJsonArray()) return; // malformed → keep last snapshot
        List<Note> parsed = new ArrayList<>();
        for (JsonElement el : obj.getAsJsonArray("notes")) {
            if (!el.isJsonObject()) continue;
            Note n = parseNote(el.getAsJsonObject());
            if (n != null) parsed.add(n);
        }
        NOTES.put(playerUuid, List.copyOf(parsed));
        if (!parsed.isEmpty()) {
            LOGGER.debug("[DungeonTrain] death-note pool for {}: {} unspawned note(s)", playerUuid, parsed.size());
        }
    }

    /** Materialise one note; {@code null} if it lacks the fields needed to spawn an echo. */
    private static Note parseNote(JsonObject o) {
        if (!o.has("id") || !o.has("deathCarriage")) return null;
        try {
            int id = o.get("id").getAsInt();
            int carriage = o.get("deathCarriage").getAsInt();
            return new Note(id, str(o, "authorUuid"), str(o, "authorName"), str(o, "authorSkinRef"),
                    carriage, str(o, "worldKey"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
