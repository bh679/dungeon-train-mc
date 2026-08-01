package games.brennan.dungeontrain.narrative;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Server-side cache of the backer name pool — the strings people who supported the project have
 * bought a place for in the game's generated content, fetched from the relay's {@code /backers/pool}
 * endpoint and handed to {@link AinBackerOverlay} to inject into AdventureItemNames.
 *
 * <p>Server-side on purpose: AIN bakes generated names into {@code CUSTOM_NAME} components on the
 * server and syncs them as ordinary item/entity data, so a client-side fetch would arrive far too
 * late to affect naming. (The existing {@code DonationSummaryClient} is client-side and is not
 * reusable here for exactly that reason.)</p>
 *
 * <p>The fetch is fire-and-forget off-thread with its own {@link HttpClient}, matching
 * {@code DeathNotePool}/{@code NarrativePool}/{@code SharedBookPool}. It never throws and never
 * blocks: a failed, slow or empty response leaves the previous snapshot in place, and a player with
 * no network sees exactly the naming they would have seen without this feature.</p>
 *
 * <p>Anonymous — no uuid, no player name, no query parameters. The same list is served to everyone,
 * so there is nothing here to gate on the network-consent setting.</p>
 */
public final class BackerPool {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Caps mirroring the relay's own (backers.js MAX_POOL / MAX_POOL_NAME / MAX_POOL_PHRASE). The
     * relay already enforces these; re-enforcing here means a compromised or simply out-of-date
     * relay can't push an over-long string into an item name, and it keeps the sanitizer honest.
     */
    static final int MAX_ENTRIES = 500;
    static final int MAX_NAME_LEN = 32;
    static final int MAX_PHRASE_LEN = 64;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // relay is HTTP/1.1; avoids h2c against a bare-Node relay (matches DeathNotePool/NarrativePool)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** Immutable current snapshot. Swapped wholesale; never mutated in place. */
    private static volatile Snapshot current = Snapshot.EMPTY;
    /** Guards against overlapping refreshes stacking up. */
    private static volatile boolean inFlight = false;

    /** The strings currently available for injection. */
    public record Snapshot(List<String> names, List<String> phrases) {
        static final Snapshot EMPTY = new Snapshot(List.of(), List.of());

        public boolean isEmpty() { return names.isEmpty() && phrases.isEmpty(); }
    }

    private BackerPool() {}

    /** The current snapshot — never null, empty until a fetch succeeds. */
    public static Snapshot snapshot() {
        return current;
    }

    /**
     * Fetch the pool off-thread and swap in the new snapshot, then re-apply the AIN overlay.
     * Safe to call repeatedly (server start, player login); overlapping calls are dropped.
     */
    public static void refresh() {
        if (inFlight) return;
        inFlight = true;
        try {
            String url = DungeonTrain.relayBaseUrl() + "/backers/pool";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] backer-pool fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] backer-pool fetch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            applyResponse(resp.body());
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] backer-pool parse failed: {}", t.toString());
                        } finally {
                            inFlight = false;
                        }
                    });
        } catch (Throwable t) {
            inFlight = false;
            LOGGER.debug("[DungeonTrain] backer-pool refresh failed to start: {}", t.toString());
        }
    }

    /** Parse a relay response body, publish the snapshot, and push it into AIN. Package-private for tests. */
    static void applyResponse(String body) {
        Snapshot parsed = parse(body);
        if (parsed == null) return; // malformed → keep the last good snapshot
        current = parsed;
        AinBackerOverlay.apply(parsed);
        LOGGER.info("[DungeonTrain] backer name pool: {} names, {} phrases",
                parsed.names().size(), parsed.phrases().size());
    }

    /**
     * Parse {@code {"ok":true,"names":[...],"phrases":[...]}} into a snapshot, or null when the body
     * is malformed or reports failure — the caller then keeps whatever it already had.
     *
     * <p>An {@code ok} response carrying empty arrays is NOT malformed: it is the legitimate "there
     * are no backers yet" answer and must produce an empty snapshot so the overlay stands down.</p>
     */
    static Snapshot parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("ok") || !obj.get("ok").getAsBoolean()) return null;
        return new Snapshot(
                readStrings(obj, "names", MAX_NAME_LEN),
                readStrings(obj, "phrases", MAX_PHRASE_LEN));
    }

    /** Read, sanitize, dedupe and cap one string array. A missing/!array key reads as empty. */
    private static List<String> readStrings(JsonObject obj, String key, int maxLen) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) return List.of();
        JsonArray arr = obj.getAsJsonArray(key);
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (out.size() >= MAX_ENTRIES) break;
            if (el == null || !el.isJsonPrimitive()) continue;
            String clean = AinBackerOverlay.sanitize(el.getAsString(), maxLen);
            if (clean.isEmpty()) continue;
            if (!seen.add(clean.toLowerCase(java.util.Locale.ROOT))) continue;
            out.add(clean);
        }
        return List.copyOf(out);
    }

    /**
     * Forget the current snapshot, returning to the pristine post-boot state. Called on server stop
     * so a previous world's pool can't leak into the next world loaded in the same JVM.
     */
    public static void reset() {
        current = Snapshot.EMPTY;
        inFlight = false;
    }

    /** Tests only: install a snapshot without touching the network. */
    static void setForTest(Snapshot snapshot) {
        current = snapshot == null ? Snapshot.EMPTY : snapshot;
    }
}
