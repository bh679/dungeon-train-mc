package games.brennan.dungeontrain.advancement.requirement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The relay's requirement overrides — the operator's live values for advancement milestones —
 * fetched once per session and cached to disk so an offline launch keeps the last-known set.
 *
 * <p>Same posture as {@code CheatModListFetcher}: own HTTP/1.1-pinned client, fire-and-forget,
 * no-throw, and anonymous — the request carries nothing about the player, so it runs regardless
 * of the network-consent setting. What comes back is public balance data the jar could equally
 * have shipped. Runs on dedicated servers too, which is why this lives in common code: the
 * server is where advancements are evaluated.</p>
 *
 * <p>Precedence: the last successful fetch this session, else the disk cache, else nothing (the
 * jar's values). The datapack apply reads {@link #effective()} synchronously; it never waits for
 * the network. If the fetch lands AFTER a server has already applied a different set, one
 * {@code reloadResources} brings the running server into line — the same work {@code /reload}
 * does, once, and only when the operator has changed something since this machine last saw
 * the relay.</p>
 *
 * <p>Cache file: {@code <gameDir>/dungeontrain/advancement-requirements.json} — beside the other
 * player-adjacent data, NOT under {@code config/} which pack updates replace.</p>
 */
public final class AdvancementRequirementOverrides {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Relay route, relative to the branch-routed capability base. */
    public static final String ROUTE = "/advancement-requirements";

    /** Cache file name under {@link PlayerDataPaths#root()}. */
    public static final String CACHE_FILE = "advancement-requirements.json";

    /** Nothing shipped needs more than a few million ticks; this bounds a mistyped override. */
    public static final long MAX_VALUE = 1_000_000_000L;

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static volatile boolean attempted;
    private static volatile boolean failed;

    /** The last successful fetch this session, or null before one lands. */
    private static volatile Map<ResourceLocation, Long> fetched;
    /** The disk cache, read once; empty when there is none. */
    private static volatile Map<ResourceLocation, Long> cached;
    /** What the running server's last datapack apply used — compared against a late fetch. */
    private static volatile Map<ResourceLocation, Long> applied;

    private AdvancementRequirementOverrides() {}

    /** Kick off the one-per-session relay fetch (retrying a previously failed attempt). No-throw. */
    public static void ensureFetched() {
        if (attempted && !failed) return;
        attempted = true;
        failed = false;
        fetchAsync();
    }

    /** The overrides in force right now: fetched, else cached, else none. Never null. */
    public static Map<ResourceLocation, Long> effective() {
        Map<ResourceLocation, Long> f = fetched;
        if (f != null) return f;
        Map<ResourceLocation, Long> c = cached;
        if (c == null) {
            c = readCache();
            cached = c;
        }
        return c;
    }

    /** Record what a datapack apply used, so a later fetch can tell whether it changed anything. */
    public static void markApplied(Map<ResourceLocation, Long> used) {
        applied = used;
    }

    static void fetchAsync() {
        try {
            String url = DungeonTrain.relayBaseUrl() + ROUTE;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    try {
                        if (err != null) {
                            LOGGER.debug("[DungeonTrain] requirement overrides fetch failed: {}", err.toString());
                            failed = true;
                            return;
                        }
                        if (resp.statusCode() / 100 != 2) {
                            LOGGER.debug("[DungeonTrain] requirement overrides fetch -> HTTP {}", resp.statusCode());
                            failed = true;
                            return;
                        }
                        accept(resp.body(), parse(resp.body()));
                    } catch (Throwable t) {
                        LOGGER.debug("[DungeonTrain] requirement overrides parse failed: {}", t.toString());
                        failed = true;
                    }
                });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] requirement overrides request failed to start: {}", t.toString());
            failed = true;
        }
    }

    /** A successful fetch: remember it, cache it, and re-apply on a server that used something else. */
    private static void accept(String body, Map<ResourceLocation, Long> units) {
        fetched = units;
        writeCache(body);
        LOGGER.info("[DungeonTrain] Advancement requirement overrides updated from relay ({} value(s)).",
            units.size());
        Map<ResourceLocation, Long> used = applied;
        if (used == null || used.equals(units)) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isRunning()) return;
        LOGGER.info("[DungeonTrain] Requirement overrides changed since this server loaded its "
            + "advancements — reloading data packs once to apply them.");
        server.execute(() -> server.reloadResources(server.getPackRepository().getSelectedIds())
            .exceptionally(t -> {
                LOGGER.warn("[DungeonTrain] Data pack reload for requirement overrides failed: {}", t.toString());
                return null;
            }));
    }

    /**
     * Parse the relay payload {@code {"units": {"<id>": {"field": ..., "value": N, ...}}}} into
     * id → value, dropping anything malformed: an id outside {@code dungeontrain:dungeon_train/},
     * a field that is not a {@link RequirementField}, a value that is not a positive integer
     * within {@link #MAX_VALUE}. The field is checked here even though the rewrite locates it from
     * the JSON, because a unit for the wrong field is an operator mistake worth refusing loudly
     * rather than silently applying to whatever field the advancement has.
     */
    public static Map<ResourceLocation, Long> parse(String body) {
        Map<ResourceLocation, Long> out = new LinkedHashMap<>();
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return Collections.unmodifiableMap(out);
        JsonElement unitsEl = root.getAsJsonObject().get("units");
        if (unitsEl == null || !unitsEl.isJsonObject()) return Collections.unmodifiableMap(out);
        for (Map.Entry<String, JsonElement> e : unitsEl.getAsJsonObject().entrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(e.getKey());
            if (!RequirementJsonRewriter.isOurs(id, DungeonTrain.MOD_ID)) continue;
            if (!e.getValue().isJsonObject()) continue;
            JsonObject unit = e.getValue().getAsJsonObject();
            JsonElement field = unit.get("field");
            if (field == null || !field.isJsonPrimitive()
                || RequirementField.byKey(field.getAsString()).isEmpty()) continue;
            JsonElement value = unit.get("value");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) continue;
            long v;
            try {
                v = value.getAsLong();
            } catch (NumberFormatException ex) {
                continue;
            }
            if (v <= 0 || v > MAX_VALUE) continue;
            out.put(id, v);
        }
        return Collections.unmodifiableMap(out);
    }

    // ---- disk cache ----

    private static Path cachePath() {
        return PlayerDataPaths.root().resolve(CACHE_FILE);
    }

    private static Map<ResourceLocation, Long> readCache() {
        try {
            Path p = cachePath();
            if (!Files.isRegularFile(p)) return Collections.emptyMap();
            return parse(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[DungeonTrain] requirement overrides cache unreadable: {}", e.toString());
            return Collections.emptyMap();
        }
    }

    /** Persist the relay payload verbatim, via a temp file + atomic move; {@link #parse} reads it back. */
    private static void writeCache(String body) {
        try {
            Path p = cachePath();
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(CACHE_FILE + ".tmp");
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[DungeonTrain] requirement overrides cache not written: {}", e.toString());
        }
    }
}
